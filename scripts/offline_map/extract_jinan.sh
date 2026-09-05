#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE_DIR="${ROOT_DIR}/tmp/offline_map_source"
SHANDONG_PBF="${SOURCE_DIR}/shandong-latest.osm.pbf"
BOUNDARY_ID="r3486449"

mkdir -p "${SOURCE_DIR}"

if [[ ! -s "${SHANDONG_PBF}" ]]; then
  echo "Missing ${SHANDONG_PBF}" >&2
  echo "Download the official Geofabrik Shandong extract first." >&2
  exit 1
fi

command -v osmium >/dev/null || { echo "osmium-tool is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

source_timestamp="$(osmium fileinfo -e -g header.option.osmosis_replication_timestamp "${SHANDONG_PBF}")"
source_sha256="$(shasum -a 256 "${SHANDONG_PBF}" | awk '{print $1}')"
jq -n \
  --arg source_url "https://download.geofabrik.de/asia/china/shandong.html" \
  --arg source_timestamp "${source_timestamp}" \
  --arg source_sha256 "${source_sha256}" \
  --arg boundary_relation "3486449" \
  '{source_url:$source_url,source_timestamp:$source_timestamp,source_sha256:$source_sha256,boundary_relation:$boundary_relation}' \
  > "${SOURCE_DIR}/source-metadata.json"

echo "Extracting the official Jinan administrative relation (${BOUNDARY_ID})..."
osmium getid "${SHANDONG_PBF}" "${BOUNDARY_ID}" -r \
  -o "${SOURCE_DIR}/jinan-boundary.osm.pbf" --overwrite
osmium export "${SOURCE_DIR}/jinan-boundary.osm.pbf" -f geojson \
  -o "${SOURCE_DIR}/jinan-boundary-all.geojson" --overwrite

jq '{type:"FeatureCollection",features:[.features[] | select(
      .geometry.type == "MultiPolygon" and
      .properties.boundary == "administrative" and
      .properties.admin_level == "5" and
      .properties.division_code == "370100"
    )]}' "${SOURCE_DIR}/jinan-boundary-all.geojson" \
  > "${SOURCE_DIR}/jinan-boundary.geojson"

feature_count="$(jq '.features | length' "${SOURCE_DIR}/jinan-boundary.geojson")"
if [[ "${feature_count}" != "1" ]]; then
  echo "Expected one Jinan boundary feature, found ${feature_count}" >&2
  exit 1
fi

echo "Cropping the Shandong PBF to the exact Jinan municipal boundary..."
osmium extract -p "${SOURCE_DIR}/jinan-boundary.geojson" "${SHANDONG_PBF}" \
  --strategy=complete_ways \
  -o "${SOURCE_DIR}/jinan.osm.pbf" --overwrite

echo "Filtering motor-vehicle roads and real OSM buildings..."
osmium tags-filter "${SOURCE_DIR}/jinan.osm.pbf" \
  'w/highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary,secondary_link,tertiary,tertiary_link,unclassified,residential,living_street,service' \
  'w/building' 'r/building' \
  -o "${SOURCE_DIR}/jinan-roads-buildings.osm.pbf" --overwrite

osmium export "${SOURCE_DIR}/jinan-roads-buildings.osm.pbf" \
  -f geojsonseq \
  --geometry-types=linestring,polygon,multipolygon \
  --add-unique-id=type_id \
  -o "${SOURCE_DIR}/jinan-roads-buildings.geojsonseq" --overwrite

echo "Prepared ${SOURCE_DIR}/jinan-roads-buildings.geojsonseq"
