# IKEA Browser network diagnostics

Debug-only Sweet Home 3D plugin that pinpoints why IKEA Browser can't reach
IKEA's servers. It runs inside Sweet Home 3D's own JRE and sandbox (Flatpak,
Snap, bundled Java), which is exactly the environment where requests fail.

## Build

```sh
make netdiag            # -> dist/IkeaNetDiag.sh3p
make netdiag-install    # also copy into the local plugins folder
```

## Use

- **In Sweet Home 3D:** double-click `IkeaNetDiag.sh3p` (or drop it in the
  plugins folder), restart, then open **Tools > IKEA network diagnostics…**
  and click **Run diagnostics**. The report is saved to
  `<SH3D folder>/IkeaBrowser/netdiag-report.txt` and **Copy report** puts it
  on the clipboard.
- **From the command line**, with any Java 8+, to compare against SH3D's JRE:

  ```sh
  java -jar IkeaNetDiag.sh3p [extra-host ...]
  ```

## What it checks

| Area | Checks |
|------|--------|
| Environment | Java version/vendor/home, OS, Flatpak/Snap sandbox, `/.flatpak-info` |
| Proxy | Java proxy properties, `*_proxy` env vars, effective `ProxySelector` per host |
| TLS config | Providers, disabled algorithms, TLS 1.2/1.3 availability, enabled cipher suites, AES-256 policy |
| Trust store | CA count, presence of GTS / GlobalSign roots that IKEA's certificates chain to |
| Resolver | `/etc/resolv.conf`, `nsswitch.conf` hosts line, IKEA overrides in `/etc/hosts` |
| Interfaces | Up/down, VPN-like interfaces (tun, wg, tailscale…). Addresses are not printed |
| Per host | System DNS vs DNS-over-HTTPS (Google, Cloudflare); sinkhole detection; TCP 443; TLS default / 1.2 only / 1.3 only / no SNI; presented certificate chain (flags intercepting issuers); plain HTTP on port 80 |
| Plugin requests | The exact search, `exists` and `model` requests IKEA Browser makes, with and without proxy |

Hosts tested: `sik.search.blue.cdtapps.com`, `web-api.ikea.com`,
`www.ikea.com`, plus control hosts `www.google.com`, `github.com` and
`www.cloudflare.com`. The report ends with a summary of likely causes.
