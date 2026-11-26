# Patches

> Generated from `patches-list.json` — **v1.1.0** (`main`) · **2 patches** across **1 app** · back to [README](README.md)

---

## Gboard (com.google.android.inputmethod.latin)

**Supported versions:** `18.6.10.1002251336-release-arm64-v8a`

| Patch | Details |
|---|---|
| **Enable Scan text** | Shows Gboard's Scan text tool (take a photo of words to type them). It is off by default and normally enabled remotely by Google, which the network guard prevents. |
| **Network guard** | Blocks all network requests from Gboard except for hosts in an allowlist. Adds a 'Network access' screen to Gboard settings to edit the allowlist and view a request log. Emoji Kitchen is allowed by default.<br><sub>Options: Default allowlist, Block Play services hand-offs, Enable blocking by default</sub> |

---
