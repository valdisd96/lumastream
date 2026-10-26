# Writing release notes

Release notes help someone decide whether to download and how to get started. Keep engineering detail in the [changelog](../CHANGELOG.md) and [maintainer guide](maintainer-guide.md), not ahead of the benefits.

## A compact release body

1. **Headline and status:** say what improves for the user; clearly label previews and give the minimum Android version.
2. **Highlights:** three or four concrete benefits, with technical detail only where it changes a user's decision.
3. **Download and setup:** direct links to the exact published APK and `SHA256SUMS`, checksum instructions, simple installation steps, signed-update compatibility, the debug-uninstall data-loss warning, and any required personal credentials. Never include an actual token.
4. **Verification and limits:** distinguish physical hardware from emulators, packaged ABIs from exercised runtimes, and observed video from confirmed audible output. State unsupported behavior and link the detailed evidence. Do not imply old evidence was rerun for the new release.

Use [0.8.0's release body](releases/0.8.0.md) as the example. Prefer short, readable sections over a dump of commits or test counts.

## Screenshots and privacy

Screenshots are optional. Use real app captures, identify their device/layout context, and credit artwork and media with source/license links where applicable. Never pass a mockup off as an exercised screen. Review captures for tokens, account details, private network/device information and personal watch history before publishing. Keep attribution next to the gallery or in a clearly linked credits section; do not imply media or metadata providers endorse LumaStream.

## Publishing or refreshing prose

- Inspect the exact preview with `gh release view v0.8.0`, substituting the intended tag. An unqualified `gh release view` may fail when every release is a preview.
- Link previews through `/releases/tag/v0.8.0`, not `/releases/latest`; asset URLs must use the matching tag and exact published filename.
- Check repository-relative links in the tracked note. Before using it as a hosted GitHub release body, expand documentation links to absolute repository URLs on the branch or commit containing those documents: release pages do not provide the same relative-link context as repository files. Do not point new documentation at an old tag that lacks it.
- Review the rendered body and its downloads before publishing. A prose-only edit must leave the APK, `SHA256SUMS`, signing certificate, version code, tag, preview status and other release metadata unchanged. Do not rebuild, re-sign or replace assets to improve the description.
- Publish only after the intended release-body edit is approved. `gh release edit TAG --notes-file PATH` changes the hosted body; keep that operation separate from drafting documentation and preserve the detailed historical evidence in the changelog.
