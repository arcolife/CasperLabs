#!/bin/bash

# https://casperlabs.atlassian.net/browse/OP-631

# make build-client-contracts
# sbt benchmarks/universal:stage

./benchmarks/target/universal/stage/bin/casperlabs-benchmarks \
    --host localhost \
    benchmark \
    --initial-funds-private-key ~/.casperlabs/validator-private.pem \
    --initial-funds-public-key ~/.casperlabs/validator-public.pem \
    --output benchmarking_stats.csv.txt
