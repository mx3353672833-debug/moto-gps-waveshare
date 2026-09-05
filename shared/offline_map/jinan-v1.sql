-- MOTO GPS offline map database v1.
-- Coordinates are signed decimal degrees * 1e6 in GCJ-02.
-- Geometry blobs contain repeated little-endian [int32 lat_e6, int32 lon_e6].
-- Building rings are implicitly closed: do not repeat the first point.

PRAGMA application_id = 0x4d475053; -- "MGPS"
PRAGMA user_version = 1;

CREATE TABLE metadata (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) WITHOUT ROWID;

INSERT INTO metadata(key, value) VALUES
    ('schema_version', '1'),
    ('coordinate_system', 'GCJ-02'),
    ('licence', 'ODbL-1.0'),
    ('attribution', '© OpenStreetMap contributors'),
    ('attribution_url', 'https://www.openstreetmap.org/copyright');

CREATE TABLE roads (
    id INTEGER PRIMARY KEY,
    osm_way_id INTEGER,
    class INTEGER NOT NULL CHECK (class BETWEEN 0 AND 5),
    min_lat_e6 INTEGER NOT NULL,
    max_lat_e6 INTEGER NOT NULL,
    min_lon_e6 INTEGER NOT NULL,
    max_lon_e6 INTEGER NOT NULL,
    points BLOB NOT NULL,
    CHECK (min_lat_e6 <= max_lat_e6),
    CHECK (min_lon_e6 <= max_lon_e6),
    CHECK (length(points) >= 16 AND length(points) % 8 = 0)
);

-- 0 motorway, 1 primary, 2 secondary, 3 residential, 4 service, 5 other.
CREATE VIRTUAL TABLE road_rtree USING rtree_i32(
    id,
    min_lat_e6, max_lat_e6,
    min_lon_e6, max_lon_e6
);

CREATE TABLE buildings (
    id INTEGER PRIMARY KEY,
    osm_way_id INTEGER,
    name TEXT,
    class INTEGER NOT NULL CHECK (class BETWEEN 0 AND 2),
    min_lat_e6 INTEGER NOT NULL,
    max_lat_e6 INTEGER NOT NULL,
    min_lon_e6 INTEGER NOT NULL,
    max_lon_e6 INTEGER NOT NULL,
    points BLOB NOT NULL,
    CHECK (min_lat_e6 <= max_lat_e6),
    CHECK (min_lon_e6 <= max_lon_e6),
    CHECK (length(points) >= 24 AND length(points) % 8 = 0)
);

-- 0 generic, 1 landmark, 2 parking.
CREATE VIRTUAL TABLE building_rtree USING rtree_i32(
    id,
    min_lat_e6, max_lat_e6,
    min_lon_e6, max_lon_e6
);
