# GeoIP join countries

MCTraveler can add the country a player joined from to the server's join
announcement. It uses MaxMind's free GeoLite2 Country CSV database.

## Setup

1. Create a [MaxMind account](https://www.maxmind.com/en/geolite2/signup).
2. Create a license key in the account portal.
3. Download the **GeoLite2 Country CSV** archive using that account.
4. Put the archive, or its three CSV files, under
   `mctraveler/geoip/` in the server directory. The extracted files may remain
   in the dated directory created by the archive.

The server can download and refresh the archive automatically when both
`CONDUIT_MAXMIND_ACCOUNT_ID` and `CONDUIT_MAXMIND_LICENSE_KEY` are set in its
environment. It downloads only when local data is absent or more than seven
days old, and keeps the existing data if a refresh fails. The credentials are
used only for the download and no MaxMind data is stored in this repository.

Join announcements omit the country while the database is loading, and for
loopback or private network addresses.

This product includes GeoLite2 data created by MaxMind, available from https://www.maxmind.com.
