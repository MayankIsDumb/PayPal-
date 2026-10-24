# Demo fixtures

The canonical payloads for the demo video are in [`../demo-assets`](../demo-assets) and are regenerated with:

```bash
./gradlew generateDemoAssets
```

The older JARs here remain as compact compatibility fixtures:

| File | Expected result |
|---|---|
| `good.jar` | Passes all checks: ZIP signature, compiled `CAFEBABE` class, manifest, valid `fabric.mod.json` |
| `bad.jar` | Blocked: no `fabric.mod.json` and no compiled classes |
| `aetheris-1920.png` | Passes a contract that requires at least 1920×1080 |
| `fake.png` | Blocked: plain text with a `.png` name, invalid PNG signature |

None of these files contain executable code paths used by the app; uploads are inspected, never run.
