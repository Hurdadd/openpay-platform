# Explore OpenPSP

[Back to the project](../../README.md)

![OpenPSP implementation map](openpsp.svg)

## Open the interactive version

Download [`openpsp.html`](openpsp.html) from GitHub using **Download raw file**, then open it in a browser. It is a standalone Archify diagram; viewing it requires no Archify installation or server. Source links require repository access.

Click a component for its details and source references. The diagram includes the current payment, callback, reconciliation, outbox publication and ledger responsibilities. The labels retain the names used in the code.

## Read it accurately

- Creating a payment persists `CREATED`; it does not authorize with a PSP.
- Status polling uses the configured in-process `SlowPsp` simulator. The callback caller denotes an external HTTP contract, not callbacks emitted by this simulator.
- The publisher sends existing unpublished outbox rows. No settlement path currently inserts those rows.
- Kafka consumption records a balanced journal and then acknowledges the message.
- The two databases are logical boundaries on one local PostgreSQL instance; the map makes no cloud or production deployment claim.

The intended journey and current integration gaps are explained in the [project introduction](../../README.md#follow-one-settlement) and [engineering reference](../engineering.md).

## Source and regeneration

The inspected revision is `78a204a785b85ccc7b3b03fcefbecfed9f0b10d6`. Each component's citations are checked against committed files at that revision. The [editable JSON](openpsp.architecture.json) keeps the topology, language and layout reproducible.

Generated using [Archify](https://github.com/tt-a1i/archify) at `d5a1333d7447c866a765adac7d4d062f2f02e4d2`. With that skill available, from the project root:

```bash
node /path/to/archify/archify/bin/archify.mjs finalize architecture \
  docs/architecture/openpsp.architecture.json \
  docs/architecture/openpsp.html --repo-root . --quality showcase --json
```

Set `ARCHIFY_CHROME` to a Chrome/Chromium executable when it is not detected automatically. Use the viewer's **Export → SVG** action to refresh the README image after a verified diagram update. Refresh source references and the pinned revision whenever the underlying code changes.

The generated viewer includes Archify code distributed under its [MIT license](ARCHIFY-LICENSE.txt).
