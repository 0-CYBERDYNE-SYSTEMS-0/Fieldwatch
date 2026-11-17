# Fieldwatch suite integration

The canonical suite implementation contract is in the sibling Station project:
[SUITE-IMPLEMENTATION-SPEC.md](../../fieldwatch-station/docs/SUITE-IMPLEMENTATION-SPEC.md).
This link assumes the current workspace's `Fieldwatch` and `fieldwatch-station` directories are siblings. When sharing this repository separately, include that specification rather than treating this pointer as a standalone contract.

The contract covers both phones, Station, RTL-SDR V4, and Flipper/BFFB v1. Mobile ownership is primarily phases S0–S3 and S6: handset qualification, durable observation recording, queued delivery, complete drone evidence, and signal context. Station owns receipt/storage/query contracts. The current source baseline is upstream 1.1.24 (`39559ac`) plus the custom Station feed.

The official signed app's initial integration is file export/import. Automatic upload belongs to the custom Station app; installing an official upstream APK does not add that publisher. Existing logs are sampled and cannot substitute for the proposed ingestion journal. The specification is proposed implementation work, not a claim that reliable offline sync or all drone transports are already supported.
