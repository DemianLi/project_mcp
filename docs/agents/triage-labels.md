# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

Edit the right-hand column to match whatever vocabulary you actually use.

> **Created on GitHub.** All five labels exist in `DemianLi/project_mcp`:
> `needs-triage` (#fbca04), `needs-info` (#c5def5), `ready-for-agent` (#0e8a16),
> `ready-for-human` (#1d76db), and `wontfix` (#ffffff — GitHub's default label,
> kept as shipped). Apply them with `gh issue edit <n> --add-label "<label>"`.
