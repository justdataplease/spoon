# Security and Firebase safeguards

The app works locally without a Firebase account. Forks should use their own
Firebase project and keep `app/google-services.json` out of Git. Never include
service-account credentials, access tokens, passwords, or signing keys in the
repository or an APK.

Firebase client API keys are public identifiers, not administrator credentials.
Access is protected by authentication, Firestore rules, and API restrictions.
See [Firebase's API-key guidance](https://firebase.google.com/docs/projects/api-keys).

## Maintainer project protections

The following live settings were checked on 2026-09-11. They apply to the
maintainer's Firebase project; cloning this repository does not apply them to a
different project.

- Public account creation is disabled on the server. Existing email/password
  accounts can still sign in; new accounts must be provisioned by the maintainer.
- Anonymous and phone authentication are disabled. SMS verification has a daily
  project quota of **zero**.
- Authentication queries are limited to **300 per minute per project** and
  **60 per minute per quota user**. Account-information queries have a separate
  **120 per minute per project** limit. These are Google API quotas, not an
  application-side counter or a limit on the number of Firebase accounts.
- Both Firebase client keys allow only the required Firebase APIs: Firebase,
  Cloud Logging, Identity Toolkit, Secure Token, Datastore, Firestore, and
  Firebase Rules. The Android key also requires package `com.spoon.app` and
  the published APK's signing certificate.
- Firestore denies unauthenticated access, isolates personal data by account,
  validates client writes, and blocks client changes to the recipe catalog.
  Raw imported payloads are inaccessible to mobile clients.
- GitHub secret scanning and push protection are enabled. Workflow runs from
  external pull-request contributors require approval. The catalog workflow
  uses short-lived Google credentials restricted to this repository's immutable
  identity, `main`, and scheduled events.

Low authentication quotas may temporarily prevent sign-in during heavy traffic;
local app use remains available. Review normal usage before increasing them.
Android package/certificate restrictions are an extra check, not proof that a
request comes from an unmodified app.

## Costs and limits

**Billing is enabled. These abuse protections are not a monetary spending cap.**
Firestore operations, storage, and scheduled catalog imports can still incur
charges. Firestore's free daily allowance does not cap paid usage on a billed
project. Budget alerts do not stop usage.

For a strict no-overage setup, use Firebase's Spark plan without a linked billing
account and accept its service limits. Exhausting free allowances can interrupt
cloud sync and imports. Local app features continue to work. See
[Firebase pricing plans](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans).

App Check is not currently enforced: the shipped app does not yet include an
App Check provider. Enforcing it without updating the app would block cloud
access for existing installations.

## Verification

- Scanned all 43 reachable commits through `9eeec55`, including 1,073 Git blobs
  and 14 archives, for private keys, privileged tokens, and credential files.
  No privileged credentials were found. APKs contain the expected public
  Firebase client key; test credentials belong to isolated demo fixtures.
- Expanded `.gitignore` to cover Firebase configuration, service-account and
  local credential files, signing material, and release binaries.
- All **18 Firestore emulator tests passed**, including unauthenticated access,
  account isolation, read-only catalog access, and blocked raw payloads.
  The deployed rules match the tested `firestore.rules` source.
- Read back the server signup/provider settings, effective authentication
  quotas, and both API-key restrictions after applying the safeguards.
- Non-mutating live checks confirmed that the APK's Android metadata reaches
  Auth and token validation, missing Android metadata is rejected, and
  unauthenticated Firestore access is denied. A real user's sign-in and sync
  were not exercised during this review.

Repository scanning is a point-in-time check, not a guarantee against future
exposure. Keep credentials out of commits even when a file is ignored.
