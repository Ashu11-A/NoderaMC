# 07 — Data Format

Everything spark writes is Protocol Buffers. Schemas live in the vendored copy at
`spark-common/src/main/proto/spark/` (`spark.proto`, `spark_sampler.proto`, `spark_heap.proto`,
`spark_ws.proto`).

## File types

| Extension | Contents | Written by |
|---|---|---|
| `.sparkprofile` | `SamplerData` — a CPU or allocation profile | `/spark profiler stop --save-to-file` |
| `.sparkheap` | `HeapData` — a heap *summary* (class histogram) | `/spark heapsummary --save-to-file` |
| `.hprof` | a standard JVM heap dump (`.phd` on OpenJ9) | `/spark heapdump` |

Names come from `SparkPlatform.resolveSaveFile(prefix, extension)`:

```java
return pluginFolder.resolve(prefix + "-" + DATE_TIME_FORMATTER.format(LocalDateTime.now()) + "." + extension);
```

so `profile-2026-07-26_18.47.38.sparkprofile`, in the platform's plugin directory
([02](./02-build-system.md) has the per-platform table). Note there is **no capture identifier in
the filename** — only a timestamp. That is why `scripts/lib/spark.sh` correlates a capture to a
suite stage using a marker file's mtime rather than by name.

## `SamplerData`

```proto
message SamplerData {
  SamplerMetadata metadata = 1;
  repeated ThreadNode threads = 2;
  map<string, string> class_sources = 3;   // optional
  map<string, string> method_sources = 4;  // optional
  map<string, string> line_sources = 5;    // optional
  repeated int32 time_windows = 6;
  map<int32, WindowStatistics> time_window_statistics = 7;
  SocketChannelInfo channel_info = 8;
}
```

Fields 3–5 are the attribution maps ([05](./05-source-attribution.md)). Their key encodings:

| Map | Key format | Example |
|---|---|---|
| `class_sources` | `className` | `dev.nodera.mod.server.entity.EntityCaptureBridge` |
| `method_sources` | `className;methodName;methodDescriptor` | `com.foo.Bar;tick;(Lnet/minecraft/world/level/Level;)V` |
| `line_sources` | `className;lineNumber` | `com.foo.Bar;123` |

`MethodCall.toString()` in `ClassSourceLookup` is where the `;`-joined form is defined.

### Metadata worth reading

```proto
message SamplerMetadata {
  CommandSenderMetadata creator = 1;
  int64 start_time = 2;
  int32 interval = 3;               // MICROseconds in execution mode; BYTES in allocation mode
  ThreadDumper thread_dumper = 4;
  DataAggregator data_aggregator = 5;
  string comment = 6;               // --comment
  PlatformMetadata platform_metadata = 7;
  PlatformStatistics platform_statistics = 8;
  SystemStatistics system_statistics = 9;
  map<string, string> server_configurations = 10;
  int64 end_time = 11;
  int32 number_of_ticks = 12;
  map<string, PluginOrModMetadata> sources = 13;   // every installed mod/plugin
  map<string, string> extra_platform_metadata = 14;
  SamplerMode sampler_mode = 15;    // EXECUTION = 0, ALLOCATION = 1
  SamplerEngine sampler_engine = 16; // JAVA = 0, ASYNC = 1
  string sampler_engine_version = 17;
}
```

Two traps live here:

1. **`interval` is microseconds** in execution mode. A raw `4000` means 4 ms. spark's own display
   divides by 1000 (`SamplerMode.EXECUTION`'s value transformer). In allocation mode the same field
   is a byte count and must not be scaled.
2. **`sampler_engine` is what actually ran**, not what was asked for. Check it before comparing two
   profiles — a Java-engine capture and an async capture are not comparable
   ([03](./03-profiler-engines.md)).

The nested `DataAggregator` carries the tick filter:

```proto
message DataAggregator {
  Type type = 1;                       // SIMPLE = 0, TICKED = 1
  ThreadGrouper thread_grouper = 2;    // BY_NAME = 0, BY_POOL = 1, AS_ONE = 2
  int64 tick_length_threshold = 3;
  int32 number_of_included_ticks = 4;
}
```

And `PluginOrModMetadata`, the installed inventory:

```proto
message PluginOrModMetadata {
  string name = 1; string version = 2; string author = 3;
  string description = 4; bool builtin = 5;
}
```

## The tree is not a tree

This is the one structural surprise, and it is documented in the exporter's own comment. Nodes are
**flattened into an array**, with children referenced by index:

> ```
> {                                     [
>   data: 'one',                          { data: 'one',   children: [1, 2] },
>   children: [                           { data: 'two',   children: [3]    },
>     { data: 'two',                      { data: 'three', children: []     },
>       children: [{ data: 'four' }] },   { data: 'four',  children: []     }
>     { data: 'three' }                 ]
>   ]
> }
> ```

```proto
message ThreadNode {
  string name = 1;
  reserved 2;                            // was "time"
  repeated StackTraceNode children = 3;  // THE FLAT ARRAY of all descendants
  repeated double times = 4;
  repeated int32 children_refs = 5;      // indices of this thread's DIRECT children
}

message StackTraceNode {
  reserved 1, 2;                         // were "time", "children"
  string class_name = 3;
  string method_name = 4;
  int32 parent_line_number = 5;
  int32 line_number = 6;
  string method_desc = 7;                // async engine only
  repeated double times = 8;
  repeated int32 children_refs = 9;      // indices into ThreadNode.children
}
```

So `ThreadNode.children` is every descendant at any depth, and the shape is reconstructed from
`children_refs`. Any reader that treats field 3 as "direct children" will double-count massively.

`times` is a `repeated double` with **one entry per time window**, not a single total — sum it.

## Reading a profile: `scripts/lib/sparkprofile.py`

A pure-standard-library reader. The harness already requires `python3` and nothing else; adding a
`protobuf` dependency to that contract just to read a diagnostic artifact would be a bad trade, and
the wire format is simple enough to decode directly.

```bash
scripts/lib/sparkprofile.py profile.sparkprofile
scripts/lib/sparkprofile.py profile.sparkprofile --thread '^Server thread$'
scripts/lib/sparkprofile.py profile.sparkprofile --source nodera --top 25
scripts/lib/sparkprofile.py profile.sparkprofile --assert-source nodera
```

| Option | Meaning |
|---|---|
| `--thread REGEX` | Restrict every number to threads matching the regex. **This is how you read the tick** — see below. |
| `--source NAME` | List the hottest frames owned by a source. Repeatable. Defaults to `nodera` and `NoderaEndpoint`. |
| `--top N` | How many frames to list per focused source (default 15). |
| `--assert-source NAME` | Exit non-zero unless that source has non-zero self time. What `scripts/e2e-profile.sh` gates on. |

### Output

```
profile: run/logs/e2e-profile/spark/profile-neoforge-server.sparkprofile
engine=async-profiler  mode=execution  interval=4ms
threads=1/95  sampled=69304.0 ms  ticks=1392
comment: profile/neoforge-server

THREAD                                 SAMPLED ms        %
------------------------------------------------------------
Server thread                             69304.0   100.0%

SOURCE                            SELF ms    %SELF   SUBTREE ms  HOTTEST OWN FRAME
--------------------------------------------------------------------------------------------------------------
(unattributed)                    69212.0    99.9%          0.0  libc.so.6.__syscall_cancel_arch_end
neoforge                             64.0     0.1%        884.0  net.neoforged.neoforge.common.extensions.IFl
nodera                               20.0     0.0%          4.0  dev.nodera.mod.server.entity.RegionDriveDebu

installed but never sampled: minecraft

hottest frames in 'nodera' (self ms):
         8.0   0.01%  dev.nodera.mod.server.entity.RegionDriveDebug.trackRegions
```

Column meanings are defined in [05](./05-source-attribution.md); briefly, **SELF** is a source's
own executing bytecode and **SUBTREE** is everything under its topmost frames, including the
vanilla work it caused.

The **THREAD table exists to stop you misreading the percentages.** A `--thread *` capture includes
every parked pool thread, and because the async engine profiles wall-clock time, those threads
accumulate enormous "sampled" totals that are not work. Without knowing the denominator, 0.0% is
uninterpretable.

### Two limitations of our reader, stated plainly

- **Thread names are truncated to 15 characters** when async-profiler supplies them, because that
  is the Linux `comm` limit — `C1 CompilerThre`, `Paper Common Wo`. This is upstream behaviour, not
  a parsing bug, and it means thread regexes should be prefix-shaped.
- **It does not reconstruct the full call tree for display.** It computes per-source aggregates and
  per-frame self time. For following an actual call path, use the viewer
  ([06](./06-viewer.md)) — the file is the same file.

---

**Previous:** [06 — The Viewer](./06-viewer.md) ·
**Next:** [08 — Configuration](./08-configuration.md) ·
**Index:** [README](./README.md)
