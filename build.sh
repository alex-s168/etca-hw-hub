scala-cli etca_core.scala > etca_core.v
verilator -cc etca_core.v
cd obj_dir
make -f Vetca_core.mk
cd ..
clang++ -g -ggdb sim.cc -I /usr/include/verilator/ obj_dir/Vetca_core__ALL.a obj_dir/verilated{,_threads}.o
