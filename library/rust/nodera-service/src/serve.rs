//! Running a listener until the process is asked to stop.
//!
//! Two things every service does identically and had written out three times: waiting for SIGTERM
//! or Ctrl-C, and the `select!` between `listener.accept()` and that signal. What each service does
//! *with* an accepted connection is entirely its own and stays in its own `wire` module.

use std::net::SocketAddr;

use tokio::net::{TcpListener, TcpStream};

/// Resolve when the process is asked to stop (SIGTERM or Ctrl-C).
///
/// SIGTERM matters more than the interrupt: it is what an orchestrator, a systemd unit and a
/// `docker stop` send, and a service that only handled Ctrl-C would be hard-killed on every real
/// deployment — with its drain never running and its peers never told.
pub async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        let mut term = match signal(SignalKind::terminate()) {
            Ok(term) => term,
            Err(_) => return std::future::pending().await,
        };
        tokio::select! {
            _ = term.recv() => {}
            _ = tokio::signal::ctrl_c() => {}
        }
    }
    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

/// Accept connections until `shutdown` resolves, handing each one to `on_connection`.
///
/// Returns when the shutdown future resolves; in-flight connections are **not** cancelled, because
/// they are separate tasks. That is what makes a drain a drain: a peer mid-query gets its answer
/// instead of a reset.
///
/// An accept error is logged and the loop continues. A listener that fails one accept (a
/// per-process descriptor limit, a connection reset between the SYN and the accept) is still a
/// working listener, and returning here would take the service down for a transient.
pub async fn accept_loop<F>(
    name: &str,
    listener: TcpListener,
    shutdown: impl std::future::Future<Output = ()>,
    mut on_connection: F,
) where
    F: FnMut(TcpStream, SocketAddr),
{
    tokio::pin!(shutdown);
    loop {
        tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((stream, remote)) => on_connection(stream, remote),
                Err(e) => eprintln!("{name}: accept error: {e}"),
            },
            _ = &mut shutdown => return,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    #[tokio::test]
    async fn every_accepted_connection_reaches_the_handler_and_shutdown_ends_the_loop() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let seen = Arc::new(AtomicUsize::new(0));
        let (stop, wait) = tokio::sync::oneshot::channel::<()>();

        let counted = Arc::clone(&seen);
        let loop_task = tokio::spawn(async move {
            accept_loop(
                "test",
                listener,
                async { wait.await.unwrap_or(()) },
                |_, _| {
                    counted.fetch_add(1, Ordering::SeqCst);
                },
            )
            .await;
        });

        for _ in 0..3 {
            let _ = TcpStream::connect(addr).await.unwrap();
        }
        for _ in 0..100 {
            if seen.load(Ordering::SeqCst) == 3 {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(10)).await;
        }
        assert_eq!(seen.load(Ordering::SeqCst), 3);

        stop.send(()).unwrap();
        tokio::time::timeout(std::time::Duration::from_secs(5), loop_task)
            .await
            .expect("the accept loop must return when asked to stop")
            .unwrap();
    }
}
