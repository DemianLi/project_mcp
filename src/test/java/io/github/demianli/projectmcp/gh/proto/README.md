# PROTOTYPE — throwaway. Do not merge to develop.

Answers issue #9: how is the real `gh` binary kept out of a test?

Two seams, built side by side so they can be compared by use rather than by argument.
Lives under `src/test` so none of it can reach the jar. Deliberately unpolished: no
Javadoc, no error handling beyond what makes it run, no abstraction.

The third candidate on the ticket — record/replay — is not a third seam. See the
resolution comment on #9.
