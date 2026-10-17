# Queue simulation tests

Runs the app's real queue code on a desktop JVM, with fake engines, files and
network, so the scheduling and persistence can be checked without a phone:

```
cd tests/queue-sim
gradle run                 # everything, about three minutes
gradle run --args=Kill     # only tests whose name contains "Kill"
```

What it covers: five files with the first readable before the last starts;
killing the app mid-batch and mid-file and resuming from the last saved part
with nothing redone; rate limits and dropped connections; the offline
fallback; cancel, retry and reorder; duplicate detection; atomic writes; the
resampler and silence trimming; the Groq client against a local server; and a
before/after timing comparison under the free plan's limits.
