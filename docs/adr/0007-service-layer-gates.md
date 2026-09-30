# 0007. Enforce liveness in the service layer, bound to the signed capture

- Status: Accepted
- Date: 2026-09-29

## Context

The desktop client has six screens that capture a face (resident, exception photo, onboarding, login, packet and
reviewer authentication, end-of-day approval). PS04: "The Registration Client shall not accept a face capture or
complete biometric authentication when the liveness/PAD assessment fails." If each screen checked liveness itself, a
missed or future screen would silently skip it. SBI preview frames are unsigned, so a pass on the preview says
nothing about the signed capture that is finally stored or matched.

## Decision

- Liveness is enforced in the two service methods every face goes through:
  - `BioServiceImpl.captureModality` (every capture, including `captureModalityForAuth`): `LivenessGate.admit`
    refuses an enforced face capture without a valid pass token before the device is called, then
    `LivenessGate.complete` binds the signed capture to the session.
  - `AuthenticationServiceImpl.authValidator`: refuses a face sample without `LIVENESS_DECISION=PASS` from an
    authentication workflow.
- The pass token is single use, bound to session, workflow, subject, device serial and nonce, and consumed before
  the capture (`LivenessTokenStore`).
- The gate fails closed: until a screen integrates liveness, its enforced face captures are refused
  (`REG-LIV-001`).
- Android follows the same rule in `FaceCaptureLivenessGate` and `FaceAuthFlow`: only a bound pass with a signed
  capture continues to ARC's storage or matching code.

## Consequences

- Screens only draw state and start the existing capture task at `captureReady`; they cannot skip or weaken the
  check.
- A face stored or matched is provably the face that passed (same device, same person by SFace similarity, inside
  the time window).
- Every refusal has a code (`REG-LIV-001..018`) and an audit (LV09), while the user sees only the generic message.
- Known limit: the gate trusts the workflow named in the request's context; a screen labelling a resident capture
  with a workflow whose policy is `OFF` would pass, but the packet then shows no `LIVENESS_*` evidence
  (`docs/design/desktop-liveness-services.md` section 9).

## Alternatives considered

- **Checks in each screen controller:** easy to miss one; no protection for future screens.
- **Trusting the preview result without binding:** allows capturing a different face after a live preview.
- **Device-side PAD only (SBI `padCapability`):** no final SBI specification (MOSIP issue #1117) and no devices;
  kept as optional extra input.
