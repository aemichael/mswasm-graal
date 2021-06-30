mx --dy /truffle,/compiler benchmark wasm:WASM_BENCHMARKCASES -- --jvm=server -Dwasmbench.benchmarkName=wasm_loop -- WasmBenchmarkSuite > ~/logs/wasm_loop_benchmark.txt
mx --dy /truffle,/compiler benchmark wasm:WASM_BENCHMARKCASES -- --jvm=server -Dwasmbench.benchmarkName=wasm_salsa20 -- WasmBenchmarkSuite > ~/logs/wasm_salsa20_benchmark.txt
mx --dy /truffle,/compiler benchmark wasm:WASM_BENCHMARKCASES -- --jvm=server -Dwasmbench.benchmarkName=wasm_store-load_1 -- WasmBenchmarkSuite > ~/logs/wasm_store_load_1_benchmark.txt