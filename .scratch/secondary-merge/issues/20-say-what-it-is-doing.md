# 20 — Saying what it is doing while it does it

**What to build:** A merge that tells the operator where it has got to, while it is still getting
there.

Today the command is silent from the moment it starts until the moment it prints its report.
That was a reasonable shape when every test finished in twenty seconds; on the live save it
means an hour with nothing to look at, and the only phase that shows any progress at all is
MCA Selector's — because that progress bar is the tool's, not ours.

The cost is not comfort. During the real merge the operator could not tell the audit from a
hang, could not answer "how much longer", and could not tell an operator watching a Discord
channel anything better than a guess. Working it out needed `/proc/<pid>/io` sampling, an
`ls -l /proc/<pid>/fd` to see which region file was open, and finally a `jstack` to read the
stack and discover it was in `ChunkAudit`. That is a diagnostic session, not a status line, and
it is not something the runbook can reasonably ask of somebody at 2am.

The phases already know what they are doing and how much of it is left — the relocation knows
its file count, the audit and the completion pass walk a list they built. None of it is
reported.

**Blocked by:** None.

**Status:** done

- [x] Each phase announces itself when it starts, so the sequence is visible as it happens
      rather than only in the final report
- [x] The phases that walk every chunk — the audit, the completion pass — report progress
      against a total they already know, often enough to be useful and rarely enough not to
      drown the log
- [x] Progress is legible when the output is a terminal *and* when it is a file: no reliance on
      carriage returns to overwrite a line, since the runbook now tells operators to capture the
      run with `script`
- [x] An operator can answer "which phase, and how much is left" from the output alone, with no
      `jstack`, no `/proc`, and no knowledge of the source
- [x] The final report is unchanged — it is what the operator keeps, and this ticket adds to
      what they see on the way, rather than replacing it
- [x] The timings each phase reports are recorded in the runbook, so the next migration starts
      with real numbers instead of an estimate

## Comments

### Where the estimate went wrong, for whoever writes the runbook timings

Measured on the live save: selection ~9 minutes, relocation ~48 minutes for 6M chunks
single-threaded, and the audit substantially longer than the 33 minutes inferred from a read
rate — the read total passed 229 GB against 60 GB of staged data, so the walk re-reads far more
than one pass. Nobody could have known that before tonight, which is the point: the numbers
only exist once something reports them.

### What it looks like

Each phase names what it does to the world rather than the class that does it, since the
operator reading it is answering "how much longer" and may be relaying that to people waiting
to play:

```
[merge] moving the chunks across…
[merge] moving the chunks across — done in 48m 12s
[merge] finishing the coordinates the tool left behind…
  completing overworld chunk: region file 100 of 36387  (31s elapsed)
```

The two phases that walk every region file share one reader ([StagedChunks]), so instrumenting
the walk covered both at once — and the walk was already the only thing that knew the total.

One line per hundred region files: about four hundred lines a walk on the live save. Often
enough that a stall shows inside a minute or two, rarely enough that the captured log stays
readable.

### The numbers, once there were numbers

Four live runs landed within two minutes of each other on every phase, which is the point of the
ticket: relocation 50–52 min, spot-check under a second, completion pass 26 min, audit 26 min,
audit verdict at 1h 43m–1h 45m. Recorded in `docs/merge.md` under "How long it takes".

The estimate this ticket was written against guessed the audit at 33 minutes from a read rate. It
was 26. Nobody could have known either number without the phase saying so.
