## Yaw

- Layout debugging: the homepage overlap issue was not a breakpoint bug after the early fixes. The visible collision came from the heavy bottom/right `box-shadow` on top-row cards rendering into the next section. Preserve shadow clearance on section wrappers (`.hero`, `.stream-section`, `.notes-grid`, `.features`, `.footer-grid`) with bottom padding so the next row does not sit under the shadow.
- Verification method: do not trust cropped chat screenshots alone. Reproduce with fresh rendered screenshots from both `http://127.0.0.1:8080/` and the public preview URL using Playwright before making further layout changes.
- Spacing guidance: avoid replacing section-specific spacing with one global sibling rule. That caused a regression where multiple sections overlapped at once.
