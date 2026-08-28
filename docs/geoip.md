# GeoIP join countries

MCTraveler can add the country a player joined from to the server's join
announcement. It uses DB-IP's free IP-to-Country Lite database.

## Setup

No account or license key is required. The server automatically downloads the
current month's compressed database when it has no local copy. If the current
month is not published yet, it tries the previous month.

To install a database manually, download the DB-IP IP-to-Country Lite CSV
archive and place it under `mctraveler/geoip/` in the server directory. Keep
the filename in this form:

```text
dbip-country-lite-YYYY-MM.csv.gz
```

The server searches this directory recursively and falls back to the newest
existing `.csv.gz` or `.csv` file if a refresh fails. Set
`CONDUIT_GEOIP_AUTO_DOWNLOAD=false` (or `0`) to disable all automatic
downloads and use only local data.

Join announcements omit the country while the database is loading, when no
database is available, and for loopback or private network addresses.
Database files are not committed to this repository.

[IP Geolocation by DB-IP](https://db-ip.com)
