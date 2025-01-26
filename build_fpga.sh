set -e
scala-cli etca_core.scala > etca_core.v
synlig -p "read_systemverilog $(realpath etca_core.v); synth_gowin -top TestCPU -json design.json"
~/oss-cad-suite/bin/nextpnr-himbaechel --json design.json --write output.json --vopt family=GW1N-9C --device "GW1NR-LV9QN88PC6/I5"
