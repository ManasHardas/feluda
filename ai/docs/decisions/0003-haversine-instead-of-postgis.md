# 0003 Great-circle distance in the application instead of PostGIS

**Decision.** Household locations are two nullable `double precision` columns. The correlation
engine computes great-circle (haversine) distance in Python.

**Why.** The brief allows "PostGIS or an equivalent spatial approach". A neighborhood has at most
a few hundred households and a correlation run compares a handful of them, so an index-backed
spatial query buys nothing. Avoiding the extension keeps the schema portable to any PostgreSQL,
keeps the test database a stock image, and lets the engine stay a pure function that is tested
without a database.

**Privacy effect.** Coordinates never leave the backend. Only the correlation role can read them,
and the incident stores an extent rounded up to 50 metres.

**No geocoding.** The administrator supplies coordinates when approving an address. If they are
missing, the household is treated as inside the radius and the incident lists the missing location
as missing evidence. No address is sent to an external geocoding service.

**Revisit when.** Neighborhoods exceed about a thousand households or polygon boundaries are needed.
