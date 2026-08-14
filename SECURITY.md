# Security Policy

VicinityProbe is a security-focused measurement tool. We take the security of the project and its users seriously.

## Scope

This policy applies to:

- The Android application code in this repository
- The built-in HTTP server, packet capture engine, and all network probing features
- Documentation that may cause harm if misleading

## Reporting a Vulnerability

**Do not open a public issue for security vulnerabilities.**

Please report vulnerabilities privately:

- Open a [private security advisory](https://github.com/Verlintas/VicinityProbe/security/advisories/new)
- Or email the maintainer with the subject prefix `[VicinityProbe-SEC]`

Please include:

- Affected version(s) and build number
- Steps to reproduce (device, Android version, network setup)
- Impact description
- Proof-of-concept if available

## Response Times

| Action | Expected time |
|---|---|
| Acknowledgment | within 72 hours |
| Triage / severity assessment | within 7 days |
| Fix (critical) | as soon as possible, usually within 14 days |
| Fix (non-critical) | next minor release |

## Disclosure Policy

We follow **responsible disclosure**:

- You report privately first.
- We acknowledge, investigate, and fix.
- We coordinate a public disclosure date (typically 30 days after the fix is released, or earlier for critical issues).

## Usage Boundary

This tool performs **active network probing** (port scanning, packet capture, HTTP fingerprinting, DNS comparison, SMB/TLS negotiation, etc.).

- Only use it on **networks and devices you are authorized to test**.
- Capturing traffic may collect plaintext data and DNS queries — treat captured data as sensitive.
- You are responsible for complying with the laws of your jurisdiction.

See [README.md](README.md) for the full compliance notice.
