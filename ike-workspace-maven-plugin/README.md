# ike-workspace-maven-plugin

**Documentation:** https://ike.network/ike-platform/ike-workspace-maven-plugin/

The `ws:*` plugin — 58 goals that coordinate cross-repository
operations across an IKE workspace. Where bare `git` only sees one
repo at a time, `ws:*` goals fan out across every checked-out
subproject in topological order.

## Quick start

```bash
mvn ws:overview                              # see what you have
mvn ws:sync                                  # daily-driver: pull + push
mvn ws:feature-start-publish -Dfeature=foo   # new feature branch
mvn ws:feature-finish-squash-publish -Dfeature=foo
mvn ws:help                                  # discover all goals
```

## Documentation

* [ws:* Goal Reference](https://ike.network/ike-platform/ike-workspace-maven-plugin/ws-goals.html)
  — comprehensive reference for all 58 goals.
* [Workspace Lifecycle](https://ike.network/ike-platform/ike-workspace-maven-plugin/workspace-lifecycle.html)
  — narrative tour of how the goals connect day-to-day, with a state
  machine diagram.

Goal naming follows a draft/publish split: state-mutating goals
default to a draft preview (writes a markdown report, no on-disk
changes); the `-publish` variant executes. See the linked docs.
