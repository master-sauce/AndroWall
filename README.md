# AndroWall - Android Firewall & Privacy Protection

## Overview

AndroWall is a comprehensive Android firewall application that gives you granular control over your device's network traffic. Built with privacy and security in mind, it protects you from unwanted tracking, invasive advertisements, and potential security threats by filtering network connections at the system level using a VPN-based approach.

### Key Features

- **Three traffic filtering modes**: DNS-only, HTTP/S-only, or full inspection
- **Rule-based filtering**: Create custom block/allow rules with multiple matching types
- **App-specific controls**: Enable/disable filtering on a per-app basis
- **Connection logging**: View detailed logs of DNS queries and HTTP/S requests
- **Dark/Light theme support**: Customize the appearance to your preference
- **Privacy-focused**: No telemetry or user data collection
- **Open source**: Full transparency with auditable code
- **no root**: no root is required

## Purpose & Use Cases

### Privacy Protection
AndroWall prevents your apps from contacting tracking domains and analytics services that compromise your privacy. By blocking these connections, you reduce the amount of personal data that's collected about you without your consent.

### Security Enhancement
The firewall can block connections to known malicious domains, phishing sites, and command-and-control servers. This provides an additional layer of security against malware, spyware, and other threats that might try to exfiltrate your data.

### Ad Blocking
By filtering DNS requests and HTTP/S connections, AndroWall can effectively block advertisements across all apps without requiring root access. This not only improves your user experience but also reduces data usage and battery consumption.

### Bandwidth Optimization
By preventing unnecessary background connections, AndroWall helps reduce data usage and can improve battery life by minimizing background network activity.

## How to Use

### Getting Started

1. **Install AndroWall** from a trusted source
2. **Grant permissions**: When first launched, AndroWall will request VPN permission and notification permissions
3. **Enable apps**: Navigate to the Apps tab and toggle on filtering for applications you want to protect
4. **Configure rules**: Add block/allow rules in the Rules tab to customize what traffic is filtered
5. **Start the firewall**: Press "Start" on the main screen to activate protection

### Understanding Traffic Scope

AndroWall offers three different filtering modes:

1. **DNS Only**
   - Filters only DNS queries
   - Minimal performance impact
   - Blocks based on domain names
   - Use when you want basic protection with lowest overhead

2. **HTTP/S Only**
   - Inspects HTTP and HTTPS traffic
   - Can filter based on full URLs and paths
   - More precise blocking capabilities
   - Recommended for users focused on web content filtering

3. **All Traffic**
   - Combines DNS and HTTP/S inspection
   - Most comprehensive protection
   - Slightly higher resource usage
   - Best for maximum privacy and security

### Creating Rules

You can create rules with different matching types:

- **Exact**: Matches the exact domain or URL
- **Subdomain**: Matches a domain and all its subdomains
- **Contains**: Matches domains/URLs containing the pattern
- **Prefix**: Matches domains/URLs starting with the pattern
- **Suffix**: Matches domains/URLs ending with the pattern
- **Wildcard**: Uses wildcards (* and ?) for advanced matching

### Filter Modes

- **Blacklist** (default): Blocks matched domains/URLs and allows everything else
- **Whitelist**: Allows only matched domains/URLs and blocks everything else

## Technical Details

### Architecture

AndroWall uses Android's VPNService API to intercept network traffic at the system level. This approach works without requiring root access and provides compatibility across Android versions.

### Traffic Inspection

The app implements different packet inspection strategies based on the selected traffic scope:

#### DNS-only Mode
- Creates a VPN route to only DNS servers (8.8.8.8/32)
- Inspects UDP packets on port 53
- Extracts domain names from DNS queries
- Minimal overhead with original packet parsing logic

#### HTTP/S Inspection
- Creates a full tunnel VPN (0.0.0.0/0)
- Parses TCP packets to extract:
  - TLS SNI (Server Name Indication) from HTTPS handshakes
  - HTTP Host headers from unencrypted traffic
  - Full URLs from HTTP requests

#### Full Inspection
- Combines both DNS and HTTP/S inspection
- Most comprehensive filtering capability

### Data Storage

AndroWall uses Android's Room database to store:
- Application configurations
- Filter rules
- Connection logs
- All data is stored locally on the device and never transmitted

### Performance Considerations

- DNS-only mode has minimal performance impact
- HTTP/S inspection adds slight overhead but remains efficient
- All components are optimized for low resource consumption
- Uses coroutines for non-blocking network operations

### Privacy Features

- No telemetry or analytics
- No user data collection
- All processing happens locally on device
- Open source code for full transparency

### Security Implementation

- Certificate pinning for any external connections
- Encryption of all local data storage
- Secure VPN implementation following Android best practices
- Regular security updates

## Troubleshooting

### Common Issues

**VPN won't connect**
- restart the vpn service
- Check if another VPN is active
- Ensure notification permissions are granted
- Restart the app and try again

**Some apps still showing ads**
- Those apps might be using hardcoded IP addresses
- Try switching to "All Traffic" mode for more comprehensive filtering
- Consider adding more specific rules

**Performance issues**
- Switch to DNS-only mode for minimal impact
- Disable filtering for non-critical apps
- Check if rules are too broad (wildcard rules can be resource-intensive)

### Logs and Diagnostics

View connection logs in the app to:
- Verify which domains are being blocked
- Identify apps making unexpected connections
- Fine-tune your rules for optimal protection

## Contributing

As an open source project, AndroWall welcomes contributions from the security community. Whether you're reporting bugs, suggesting features, or submitting code, your input helps improve privacy for everyone.

## License

AndroWall is released under the MIT license, allowing for free use, modification, and distribution while preserving the original copyright notice.

---

### Support the Project

If you find AndroWall useful, consider contributing to its development by:
- Reporting bugs and suggesting improvements
- Sharing the project with privacy-conscious users
- Contributing code if you're a developer
- Providing feedback on user experience