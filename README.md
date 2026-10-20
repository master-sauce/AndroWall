# AndroWall - Android Firewall & Privacy Protection

## Overview

AndroWall is a comprehensive Android firewall application that gives you granular control over your device's network traffic. Built with privacy and security in mind, it protects you from unwanted tracking, invasive advertisements, and potential security threats by filtering network connections at the system level using a VPN-based approach.

### Key Features

## Features

- Per-app firewall — select which apps traffic is intercepted
- System-wide mode — block ads on ALL apps without per-app selection
- Blacklist mode — block matched domains, allow rest
- Whitelist mode — allow matched domains, block rest
- Rule types: Exact, Subdomain, Contains, Prefix, Suffix, Wildcard (`*.example.com`)
- Global rules (apply to all enabled apps) and per-app rules
- **21 bundled ad & tracker filter lists** — EasyList, EasyPrivacy, EasyList Cookie, EasyList Hebrew, uBlock Origin ×4 (ads / unbreak / resource abuse / other annoyances), AdGuard ×6 (base / mobile / annoyances / social / Russian / Turkish), Fanboy ×2, anti-adblock ×3, Peter Lowe hosts, oisd big
- **Weekly auto-update for every enabled list** — checked at each app/service start, downloaded one at a time, one engine rebuild at the end
- **Bundled offline copies** — first launch blocks out of the box, no network needed
- **Checksum + size validation** — a truncated or corrupted download never replaces the last good copy; oversized lists are rejected
- **Per-list status & control** — ready / updating / updated / failed / empty, independent toggles, and an update button on every row
- **Memory-safe by design** — disabled lists are never parsed, heavy and foreign-language lists ship off, parsed engines are cached (no re-parsing on toggles) and DNS wildcard rules are hard-capped
- DNS activity log with search, blocked/allowed status
- Add rules directly from log entries
- Rule import/export — backup and restore rules as JSON
- Dark and light theme with persistent preference
- Persistent notification with Start/Stop/Close controls
- Notification cannot be swiped away (re-posts on dismissal)
- Privacy-focused: No telemetry or user data collection
- Open source: Full transparency with auditable code
- No root required

## Purpose & Use Cases

### Privacy Protection
AndroWall prevents your apps from contacting tracking domains and analytics services that compromise your privacy. By blocking these connections, you reduce the amount of personal data that's collected about you without your consent.

### Security Enhancement
The firewall can block connections to known malicious domains, phishing sites, and command-and-control servers. This provides an additional layer of security against malware, spyware, and other threats that might try to exfiltrate your data.

### Ad Blocking
By filtering DNS connections, AndroWall can effectively block advertisements across all apps without requiring root access. This not only improves your user experience but also reduces data usage and battery consumption.

### Bandwidth Optimization
By preventing unnecessary background connections, AndroWall helps reduce data usage and can improve battery life by minimizing background network activity.

## How to Use

### Getting Started

1. **Install apk**: Install the apk from the releases tag
2. **Grant permissions**: When first launched, AndroWall will request VPN permission and notification permissions
3. **Choose blocking scope**: Toggle "System-wide mode" on the main screen to block ads on all apps, or leave it off and enable individual apps in the Apps tab
4. **Choose mode**: Choose whitelist or blacklist mode for your preference (default is blacklist)
5. **Configure rules**: Add block/allow rules in the Rules tab to customize what traffic is filtered
6. **Toggle filter lists**: Enable or disable any of the 21 filter lists in the Rules tab — each has its own switch, status and update button
7. **Start the firewall**: Press "Start" on the main screen to activate the app service


### Creating Rules

You can create rules with different matching types:

- **Exact**: Matches the exact domain or URL
- **Subdomain**: Matches a domain and all its subdomains
- **Contains**: Matches domains/URLs containing the pattern
- **Prefix**: Matches domains/URLs starting with the pattern
- **Suffix**: Matches domains/URLs ending with the pattern
- **Wildcard**: Uses wildcards (*) for advanced matching

### Filter Modes

- **Blacklist** (default): Blocks matched domains and allows everything else
- **Whitelist**: Allows only matched domains and blocks everything else

### System-Wide Mode

System-wide mode tunnels DNS traffic from ALL apps through the firewall, applying both your custom global rules and enabled filter lists across the entire device. When enabled:

- Per-app controls are greyed out and disabled
- Only global rules apply (per-app rules become irrelevant since the VPN cannot distinguish which app made a DNS query)
- The setting persists across app restarts

### Filter Lists

AndroWall bundles **21 Adblock Plus / hosts-style filter lists** in the APK, every one independently toggleable from the Rules tab:

| List | Notes |
|---|---|
| EasyList | General ads |
| EasyPrivacy | Tracking & analytics |
| EasyList Cookie (Fanboy Cookiemonster) | Cookie consent notices |
| EasyList Hebrew | Hebrew-language ads |
| uBlock filters / Unbreak / Resource abuse / Other annoyances | uBlock Origin's own lists |
| AdGuard Base / Mobile / Annoyances / Social | AdGuard's core lists |
| AdGuard Russian / Turkish | Foreign-language lists — **off by default** |
| Fanboy Annoyances / Indian | Overlapping/foreign lists — **off by default** |
| Anti-Adblock (ABP) / Anti-Adblock Killer / Fuck Fuckadblock | Anti-adblock bypass counters |
| Peter Lowe (hosts format) | Ads + trackers |
| oisd big | 233k domains — heavy, **off by default** |

**Updates:** every enabled list refreshes on a 7-day cadence, checked at each app/service start. Downloads run sequentially (one at a time, with a short gap) and the engine is rebuilt once at the end. The update button on any row forces a refresh immediately — success shows as `updated · N rules`, failures stay on the previous cached copy.

**Safety:** downloads must pass a minimum rule count, a maximum size and — when the list publishes one — a `! Checksum:` digest before they replace the cached copy (atomic temp-file + rename; a killed process can't corrupt the active list). Disabled lists are never downloaded or parsed, and toggling one off frees its memory, because the always-on VPN process is kept lean.

**DNS-level extraction:** filter lists are reduced to what a resolver can act on — `||domain^` block rules, `@@||domain^` exceptions and hosts-file lines (`0.0.0.0 domain`). URL-path filters and CSS selectors are skipped as they cannot be evaluated at the DNS level. Wildcard domain rules are capped (per list and in the merged engine) so lookups stay fast.

### Logs and Diagnostics

View connection logs in the app to:
- Verify which domains are being blocked
- Identify apps making unexpected connections
- Fine-tune your rules for optimal protection

## Contributing

As an open source project, AndroWall welcomes contributions from the security community. Whether you're reporting bugs, suggesting features, or submitting code, your input helps improve privacy for everyone.


---


## License

MIT License

Copyright (c) 2026 master-sauce

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---



### Support the Project

If you find AndroWall useful, consider contributing to its development by:
- Reporting bugs and suggesting improvements
- Sharing the project with privacy-conscious users
- Contributing code if you're a developer
- Providing feedback on user experience
