# AndroWall - Android Firewall & Privacy Protection

## Overview

AndroWall is a comprehensive Android firewall application that gives you granular control over your device's network traffic. Built with privacy and security in mind, it protects you from unwanted tracking, invasive advertisements, and potential security threats by filtering network connections at the system level using a VPN-based approach.

### Key Features

## Features

- Per-app firewall — select which apps traffic is intercepted
- Blacklist mode — block matched domains, allow rest
- Whitelist mode — allow matched domains, block rest
- Rule types: Exact, Subdomain, Contains, Prefix, Suffix, Wildcard (`*.example.com`)
- Global rules (apply to all enabled apps) and per-app rules
- DNS activity log with search, blocked/allowed status
- Add rules directly from log entries
- Dark and light theme with persistent preference
- Persistent notification with Start/Stop/Close controls
- Notification cannot be swiped away (re-posts on dismissal)
- Privacy-focused: No telemetry or user data collection
- Open source: Full transparency with auditable code
- no root: no root is required

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
3. **Enable apps**: Navigate to the Apps tab and toggle on filtering for applications you want to protect
4. **Choose mode**: Choose whitelist or blacklist mode for you prefrence (defualt is blacklist) 
5. **Configure rules**: Add block/allow rules in the Rules tab to customize what traffic is filtered
6. **Start the firewall**: Press "Start" on the main screen to activate the app service


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


### Logs and Diagnostics

View connection logs in the app to:
- Verify which domains are being blocked
- Identify apps making unexpected connections
- Fine-tune your rules for optimal protection

## Contributing

As an open source project, AndroWall welcomes contributions from the security community. Whether you're reporting bugs, suggesting features, or submitting code, your input helps improve privacy for everyone.


---

### Support the Project

If you find AndroWall useful, consider contributing to its development by:
- Reporting bugs and suggesting improvements
- Sharing the project with privacy-conscious users
- Contributing code if you're a developer
- Providing feedback on user experience