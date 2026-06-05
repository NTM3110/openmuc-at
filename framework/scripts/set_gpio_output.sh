#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage:" >&2
  echo "  $0 <io_index> <value>" >&2
  echo "  $0 <io_index> output <value>" >&2
  echo "  $0 <io_index> input" >&2
  echo "  io_index: 0 for IO0/gpio33, 1 for IO1/gpio32" >&2
  echo "  value:    0 or 1" >&2
}

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  usage
  exit 2
fi

io_index="$1"
mode=""
value=""

if [ "$#" -eq 2 ]; then
  case "$2" in
    0|1)
      mode="output"
      value="$2"
      ;;
    input|in)
      mode="input"
      ;;
    output|out)
      echo "Missing output value. Use 0 or 1." >&2
      exit 2
      ;;
    *)
      echo "Invalid mode or value '$2'." >&2
      usage
      exit 2
      ;;
  esac
else
  case "$2" in
    output|out)
      mode="output"
      ;;
    input|in)
      echo "Input mode does not accept a value." >&2
      exit 2
      ;;
    *)
      echo "Invalid mode '$2'. Use input or output." >&2
      exit 2
      ;;
  esac
  case "$3" in
    0|1) value="$3" ;;
    *)
      echo "Invalid value '$3'. Use 0 or 1." >&2
      exit 2
      ;;
  esac
fi

case "$io_index" in
  0) gpio="33" ;;
  1) gpio="32" ;;
  *)
    echo "Invalid io_index '$io_index'. Use 0 or 1." >&2
    exit 2
    ;;
esac

gpio_dir="/sys/class/gpio/gpio${gpio}"
gpio_value="${gpio_dir}/value"
gpio_direction="${gpio_dir}/direction"

if [ ! -d "$gpio_dir" ]; then
  echo "$gpio" > /sys/class/gpio/export
fi

for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ -d "$gpio_dir" ] && break
  sleep 0.1
done

if [ ! -w "$gpio_direction" ]; then
  echo "GPIO direction is not writable: $gpio_direction" >&2
  exit 1
fi

case "$mode" in
  input)
    echo "in" > "$gpio_direction"
    ;;
  output)
    echo "out" > "$gpio_direction"
    echo "$value" > "$gpio_value"
    ;;
esac
