# Deliberate freshness fault check

Executed during the 19 September 2026 session (local time).

After the original suite passed, the assistant temporarily replaced:

```kotlin
val held = snapshot.reportThroughDate.isBefore(minimumDate)
```

with:

```kotlin
val held = false
```

The targeted `fresh download cannot make old coverage eligible` test failed:

```
1 test completed, 1 failed
expected: <HOLD> but was: <ELIGIBLE>
```

The failure was an assertion failure, not a compilation or environment error. The original implementation was restored immediately after the run.

This was deliberate fault injection to check test sensitivity. It was not an accidental bug discovered by the user, and it does not establish that the suite catches every possible defect.
