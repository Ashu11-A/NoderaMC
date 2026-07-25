#!/usr/bin/env python3
# ===========================================================================
# nodera vanilla-bot — THE UNMODIFIED CLIENT of the endpoint suites.
#
# A Minecraft-protocol client with no Minecraft in it: handshake, offline-mode
# login, the configuration phase, and enough of the play phase to stay
# connected, run a command, drop an item, and report what happened.
#
# WHY THIS INSTEAD OF A SECOND LAUNCHED GAME
#   - a vanilla client needs a Mojang launcher, an asset index and an account,
#     none of which belong in CI;
#   - two full Minecraft clients + a Paper server + three workers on one runner
#     is a memory problem, not a test;
#   - and it buys nothing, because every assertion in these suites comes from a
#     log line or a control socket. Nothing is ever read off a screen.
#
#   It is in fact MORE faithful to the thing under test: it proves the endpoint
#   is reachable by ANYTHING that speaks the protocol, with no NeoForge
#   anywhere in the process. Set NODERA_VANILLA_CLIENT=<command> to drive a real
#   vanilla client instead; the suites call it with the same arguments.
#
# EVIDENCE CONTRACT — the suites grep these lines and nothing else:
#   BOT: connected <host>:<port>
#   BOT: logged-in <uuid>
#   BOT: joined
#   BOT: sent <what>
#   BOT: dropped
#   BOT: disconnected <reason>
#   BOT: failed <reason>
#
# USAGE
#   vanilla-bot.py --host 127.0.0.1 --port 25599 --name VanillaDev \
#       --script 'wait:joined' 'cmd:nodera join hunter2' 'sleep:5' 'drop' 'hold:30'
#
#   Script steps:  wait:joined | sleep:<s> | hold:<s> | chat:<text>
#                  cmd:<command without the leading slash> | drop | quit
#
# EXIT CODES
#   0  the script ran to completion
#   1  a failure the suite should report (never reached PLAY, unexpected close)
#   2  bad arguments
#   3  the server refused us — EXPECTED by --expect-refused, a failure otherwise
#
# PACKET IDS
#   Every id lives in PROTOCOL below, keyed by protocol version. They are the
#   one part of this file that goes stale on a Minecraft update, so they are in
#   ONE table rather than sprinkled through the code, and an id the bot does not
#   recognise is IGNORED (which is what a real client does) rather than fatal.
#   Source of truth: https://minecraft.wiki/w/Java_Edition_protocol
# ===========================================================================
import argparse
import json
import os
import socket
import struct
import sys
import time
import uuid
import zlib

# --- Protocol tables -------------------------------------------------------
# 767 = Minecraft 1.21.1, the version the mod pins (docs/README.md §4.6).
PROTOCOL = {
    767: {
        "name": "1.21.1",
        # serverbound
        "sb_handshake": 0x00,
        "sb_login_start": 0x00,
        "sb_login_ack": 0x03,
        "sb_cfg_client_information": 0x00,
        "sb_cfg_plugin_message": 0x02,
        "sb_cfg_finish_ack": 0x03,
        "sb_cfg_keep_alive": 0x04,
        "sb_cfg_pong": 0x05,
        "sb_cfg_known_packs": 0x07,
        "sb_play_confirm_teleport": 0x00,
        "sb_play_chat_command": 0x04,
        "sb_play_chat_message": 0x06,
        "sb_play_keep_alive": 0x18,
        "sb_play_position": 0x1A,
        "sb_play_player_action": 0x24,
        # clientbound
        "cb_login_disconnect": 0x00,
        "cb_login_encryption": 0x01,
        "cb_login_success": 0x02,
        "cb_login_compression": 0x03,
        "cb_cfg_disconnect": 0x02,
        "cb_cfg_finish": 0x03,
        "cb_cfg_keep_alive": 0x04,
        "cb_cfg_ping": 0x05,
        "cb_cfg_known_packs": 0x0E,
        "cb_play_login": 0x2B,
        "cb_play_disconnect": 0x1D,
        "cb_play_keep_alive": 0x26,
        "cb_play_sync_position": 0x40,
    },
}


def say(msg):
    print("BOT: " + msg, flush=True)


def die(reason, code=1):
    say("failed " + reason)
    sys.exit(code)


# --- Wire primitives -------------------------------------------------------

def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_varint(read_exactly):
    result = 0
    for shift in range(0, 35, 7):
        byte = read_exactly(1)[0]
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result
    raise ValueError("varint longer than 5 bytes")


def mc_string(text):
    raw = text.encode("utf-8")
    return varint(len(raw)) + raw


class Reader:
    """Cursor over one decoded packet body. Never reads past its own buffer."""

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def take(self, n):
        if self.pos + n > len(self.data):
            raise ValueError("packet truncated")
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def varint(self):
        return read_varint(self.take)

    def string(self):
        return self.take(self.varint()).decode("utf-8", errors="replace")

    def uuid(self):
        return str(uuid.UUID(bytes=self.take(16)))


class Connection:
    """Length-prefixed, optionally zlib-compressed Minecraft framing."""

    def __init__(self, host, port, timeout):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        self.threshold = -1          # < 0 until the server sends SetCompression
        self.buffer = bytearray()

    def _recv_exactly(self, n):
        while len(self.buffer) < n:
            chunk = self.sock.recv(max(4096, n - len(self.buffer)))
            if not chunk:
                raise ConnectionError("server closed the connection")
            self.buffer.extend(chunk)
        out = bytes(self.buffer[:n])
        del self.buffer[:n]
        return out

    def send(self, packet_id, payload=b""):
        body = varint(packet_id) + payload
        if self.threshold >= 0:
            if len(body) >= self.threshold:
                body = varint(len(body)) + zlib.compress(body)
            else:
                body = varint(0) + body
        self.sock.sendall(varint(len(body)) + body)

    def recv(self):
        """@return (packet_id, Reader) for the next packet."""
        length = read_varint(self._recv_exactly)
        body = self._recv_exactly(length)
        if self.threshold >= 0:
            cursor = Reader(body)
            uncompressed = cursor.varint()
            body = zlib.decompress(cursor.data[cursor.pos:]) if uncompressed > 0 \
                else cursor.data[cursor.pos:]
        reader = Reader(body)
        return reader.varint(), reader

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def describe(raw):
    """Best-effort human text out of a chat/disconnect component."""
    try:
        parsed = json.loads(raw)
    except (ValueError, TypeError):
        return str(raw)[:200]
    if isinstance(parsed, str):
        return parsed
    if isinstance(parsed, dict):
        text = parsed.get("text", "")
        for extra in parsed.get("extra", []) or []:
            text += describe(json.dumps(extra))
        return text or json.dumps(parsed)[:200]
    return json.dumps(parsed)[:200]


# --- The three protocol phases ---------------------------------------------

def do_login(conn, ids, host, port, name, protocol):
    conn.send(ids["sb_handshake"],
              varint(protocol) + mc_string(host) + struct.pack(">H", port) + varint(2))
    # Offline mode: the UUID is the server's business, but the field is required.
    offline = uuid.uuid3(uuid.NAMESPACE_OID, "OfflinePlayer:" + name)
    conn.send(ids["sb_login_start"], mc_string(name) + offline.bytes)

    while True:
        pid, r = conn.recv()
        if pid == ids["cb_login_compression"]:
            conn.threshold = r.varint()
        elif pid == ids["cb_login_success"]:
            player = r.uuid()
            conn.send(ids["sb_login_ack"])
            return player
        elif pid == ids["cb_login_disconnect"]:
            say("disconnected " + describe(r.string()))
            sys.exit(3)
        elif pid == ids["cb_login_encryption"]:
            die("the server is in ONLINE mode — the suites stage offline-mode servers "
                "(server.properties online-mode=false)", 1)
        # anything else in the login phase is not ours to care about


def do_configuration(conn, ids):
    # A minimal, honest client-information packet: locale, view distance, chat
    # settings, skin parts, main hand, text filtering, server listing, particles.
    info = (mc_string("en_us") + bytes([10]) + varint(0) + bytes([1])
            + bytes([0x7F]) + varint(1) + bytes([0]) + bytes([1]) + varint(0))
    conn.send(ids["sb_cfg_client_information"], info)
    conn.send(ids["sb_cfg_plugin_message"],
              mc_string("minecraft:brand") + mc_string("nodera-vanilla-bot"))

    while True:
        pid, r = conn.recv()
        if pid == ids["cb_cfg_known_packs"]:
            # Echo an empty known-pack set: we accept whatever the server sends.
            conn.send(ids["sb_cfg_known_packs"], varint(0))
        elif pid == ids["cb_cfg_keep_alive"]:
            conn.send(ids["sb_cfg_keep_alive"], r.take(8))
        elif pid == ids["cb_cfg_ping"]:
            conn.send(ids["sb_cfg_pong"], r.take(4))
        elif pid == ids["cb_cfg_finish"]:
            conn.send(ids["sb_cfg_finish_ack"])
            return
        elif pid == ids["cb_cfg_disconnect"]:
            say("disconnected " + describe(r.string()))
            sys.exit(3)
        # registry data, tags, resource packs: accepted implicitly by ignoring them


def pump(conn, ids, deadline, on_joined=None):
    """Service the play phase until `deadline`. Returns False if the server closed."""
    joined = False
    while time.time() < deadline:
        try:
            pid, r = conn.recv()
        except socket.timeout:
            continue
        except (ConnectionError, OSError) as e:
            say("disconnected " + str(e))
            return False
        if pid == ids["cb_play_keep_alive"]:
            conn.send(ids["sb_play_keep_alive"], r.take(8))
        elif pid == ids["cb_play_sync_position"]:
            # x,y,z (3×f64) + yaw,pitch (2×f32) + flags (1) + teleport id (varint)
            body = r.take(8 * 3 + 4 * 2 + 1)
            conn.send(ids["sb_play_confirm_teleport"], varint(r.varint()))
            x, y, z = struct.unpack(">ddd", body[:24])
            conn.send(ids["sb_play_position"], struct.pack(">dddB", x, y, z, 1))
            if not joined:
                joined = True
                say("joined")
                if on_joined:
                    on_joined()
        elif pid == ids["cb_play_disconnect"]:
            say("disconnected " + describe(r.data[r.pos:].decode("utf-8", "replace")))
            return False
        elif pid == ids["cb_play_login"] and not joined:
            pass  # the position sync is the real "we are in the world" signal
    return True


def send_command(conn, ids, command):
    # ChatCommand: the command WITHOUT the leading slash, a timestamp, a salt,
    # an empty signature array, and the message-count/acknowledged bitset.
    payload = (mc_string(command)
               + struct.pack(">q", int(time.time() * 1000))
               + struct.pack(">q", 0)
               + varint(0)
               + varint(0) + bytes(3))
    conn.send(ids["sb_play_chat_command"], payload)


def send_chat(conn, ids, text):
    payload = (mc_string(text)
               + struct.pack(">q", int(time.time() * 1000))
               + struct.pack(">q", 0)
               + bytes([0])            # no signature
               + varint(0) + bytes(3))
    conn.send(ids["sb_play_chat_message"], payload)


def drop_held(conn, ids):
    # PlayerAction status 3 = drop the entire held stack, at (0,0,0), face 0.
    conn.send(ids["sb_play_player_action"],
              varint(3) + struct.pack(">q", 0) + bytes([0]) + varint(0))


# --- Driver ----------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="Nodera unmodified-client driver")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25599)
    ap.add_argument("--name", default="VanillaDev")
    ap.add_argument("--protocol", type=int, default=767)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--expect-refused", action="store_true",
                    help="the server MUST refuse us; reaching PLAY is the failure")
    ap.add_argument("--script", nargs="*", default=["wait:joined", "hold:20"])
    args = ap.parse_args()

    override = os.environ.get("NODERA_VANILLA_CLIENT")
    if override:
        # A real vanilla client was staged; hand the same arguments to it.
        os.execvp("/bin/sh", ["/bin/sh", "-c",
                              override + ' "$@"', "sh"] + sys.argv[1:])

    ids = PROTOCOL.get(args.protocol)
    if ids is None:
        die("no packet table for protocol %d — add one to PROTOCOL in %s"
            % (args.protocol, os.path.basename(__file__)), 2)

    try:
        conn = Connection(args.host, args.port, args.timeout)
    except OSError as e:
        die("cannot reach %s:%d (%s)" % (args.host, args.port, e))
    say("connected %s:%d" % (args.host, args.port))

    try:
        player = do_login(conn, ids, args.host, args.port, args.name, args.protocol)
        say("logged-in " + player)
        do_configuration(conn, ids)
    except SystemExit:
        raise
    except (ConnectionError, OSError, ValueError) as e:
        die("handshake/configuration failed: %s" % e)

    if args.expect_refused:
        # Give the server a generous window to close us; reaching PLAY is failure.
        if pump(conn, ids, time.time() + args.timeout):
            die("the server ADMITTED us — it was expected to refuse", 1)
        sys.exit(0)

    for step in args.script:
        verb, _, arg = step.partition(":")
        if verb == "wait" and arg == "joined":
            if not pump(conn, ids, time.time() + args.timeout):
                die("closed before reaching the world")
        elif verb in ("sleep", "hold"):
            if not pump(conn, ids, time.time() + float(arg or 5)):
                die("closed during %s" % verb)
        elif verb == "chat":
            send_chat(conn, ids, arg)
            say("sent chat")
        elif verb == "cmd":
            send_command(conn, ids, arg)
            say("sent command")
        elif verb == "drop":
            drop_held(conn, ids)
            say("dropped")
        elif verb == "quit":
            break
        else:
            die("unknown script step %r" % step, 2)
        # Keep the connection serviced between steps so a keep-alive is never missed.
        pump(conn, ids, time.time() + 1)

    conn.close()
    sys.exit(0)


if __name__ == "__main__":
    main()
