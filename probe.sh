#!/bin/bash
# Probe each slug against all 3 ATS boards. Prints "board slug count" for hits.
# Usage: ./probe.sh candidates.txt > companies.yml

while read -r slug; do
  [ -z "$slug" ] && continue

  n=$(curl -sf --max-time 8 "https://boards-api.greenhouse.io/v1/boards/$slug/jobs" \
      | jq -r '.jobs | length' 2>/dev/null)
  if [ -n "$n" ] && [ "$n" != "null" ] && [ "$n" -gt 0 ] 2>/dev/null; then
    echo "  - { board: greenhouse, slug: $slug }   # $n jobs"; continue
  fi

  n=$(curl -sf --max-time 8 "https://api.lever.co/v0/postings/$slug?mode=json" \
      | jq -r 'length' 2>/dev/null)
  if [ -n "$n" ] && [ "$n" != "null" ] && [ "$n" -gt 0 ] 2>/dev/null; then
    echo "  - { board: lever, slug: $slug }   # $n jobs"; continue
  fi

  n=$(curl -sf --max-time 8 "https://api.ashbyhq.com/posting-api/job-board/$slug" \
      | jq -r '.jobs | length' 2>/dev/null)
  if [ -n "$n" ] && [ "$n" != "null" ] && [ "$n" -gt 0 ] 2>/dev/null; then
    echo "  - { board: ashby, slug: $slug }   # $n jobs"; continue
  fi

  echo "# MISS: $slug" >&2
done < "$1"
