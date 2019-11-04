#!/bin/bash
set -euo pipefail
trap 'cleanup' EXIT

wait_until() {
    OBSERVABLE_FILE="$1"
    STRING_TO_WAIT="$2"
    tail -f "$OBSERVABLE_FILE" | while read LOGLINE
    do
	[[ "${LOGLINE}" == *"$STRING_TO_WAIT"* ]] && pkill -9 -P "$$" tail
    done
}

run_engine() {
    echo "Starting engine..."
    echo "" > /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/engine.log
    # cd /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/execution-engine/engine-grpc-server
    # 	cargo build --release
    #	casperlabs-engine-grpc-server ~/.casperlabs/.casper-node.sock &> 
    /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/execution-engine/target/release/casperlabs-engine-grpc-server \
	~/.casperlabs/.casper-node.sock &>\
	/home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/engine.log &
    wait_until "/home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/engine.log" "is listening on socket" || true
}

run_node_sbt() {
    # sbt node/universal:stage client/universal:stage benchmarks/universal:stage
    # JAVA_OPTS="-XX:+HeapDumpOnOutOfMemoryError -Xmx4096m -XX:MaxMetaspaceSize=1024m" casperlabs-node run 
    echo "Starting node..."
    cd /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/ && \
	JAVA_OPTS="-XX:+HeapDumpOnOutOfMemoryError -Xmx4096m -XX:MaxMetaspaceSize=1024m" ./node/target/universal/stage/bin/casperlabs-node run \
		 --casper-standalone \
		 --server-no-upnp \
		 --server-host=0.0.0.0 \
		 --tls-key ~/.casperlabs/node.key.pem \
		 --tls-certificate ~/.casperlabs/node.certificate.pem \
		 --casper-validator-sig-algorithm=ed25519 \
		 --casper-validator-private-key-path ~/.casperlabs/validator-private.pem \
		 --casper-validator-public-key-path ~/.casperlabs/validator-public.pem \
		 --casper-max-block-size-bytes=1048576000 \
		 # --metrics-prometheus
}

stop_engine() {
    echo "Stopping engine..."
    pkill -9 casperlabs-engine-grpc-server || true
    sleep "1"
    rm -rf /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/engine.log || true
    # rm -rf /home/arcolife/.casperlabs/blockstorage || true
    # rm -rf /home/arcolife/.casperlabs/dagstorage ||true
    rm -rf /home/arcolife/.casperlabs/global_state || true
    rm -rf /home/arcolife/.casperlabs/sqlite.db
}

run_prometheus() {
    echo "Running prometheus..."
    echo "scrape_configs:" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml
    echo "- job_name: node" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml
    echo "  scrape_interval: 500ms" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml
    echo "  scrape_timeout: 500ms" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml
    echo "  file_sd_configs:" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml
    echo "  - files:" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml
    echo "    - /etc/prometheus/targets.yml" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml
    echo "    refresh_interval: 15s" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml

    echo "- labels:" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/targets.yml
    echo "    job: node" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/targets.yml
    echo "  targets: [host.docker.internal:40403]" >> /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/targets.yml
    
    docker run \
	   --rm \
	   -d \
	   --name prometheus \
	   -p 9090:9090 \
	   -v /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml:/etc/prometheus/prometheus.yml \
	   -v /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/targets.yml:/etc/prometheus/targets.yml \
	   prom/prometheus:v2.7.1
}

# run_node_bloop() {
#     echo "Starting node..."
#     cd /Users/igorramazanov/projects/casperlabs/CasperLabs/ && \
    # 	bloop run node \
    # 	      --args run \
    # 	      --args '--casper-standalone' \
    # 	      --args '--server-host=0.0.0.0' \
    # 	      --args '--server-use-gossiping' \
    # 	      --args '--server-no-upnp' \
    # 	      --args '--casper-validator-sig-algorithm=ed25519' \
    # 	      --args '--casper-validator-public-key-path=/Users/igorramazanov/.casperlabs/validator-public.pem' \
    # 	      --args '--casper-genesis-account-public-key-path=/Users/igorramazanov/.casperlabs/validator-public.pem' \
    # 	      --args '--casper-validator-private-key-path=/Users/igorramazanov/.casperlabs/validator-private.pem' \
    # 	      --args '--casper-bonds-file=/Users/igorramazanov/.casperlabs/genesis/bonds.txt' \
    # 	      --args '--metrics-prometheus' \
    # 	      --args '--casper-mint-code-path=/Users/igorramazanov/.casperlabs/mint_token.wasm' \
    # 	      --args '--casper-pos-code-path=/Users/igorramazanov/.casperlabs/pos.wasm' \
    # 	      --args '--casper-initial-motes=1234567890'
# }

stop_prometheus() {
    echo "Stop prometheus"
    docker stop prometheus || true
    rm /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/prometheus.yml || true
    rm /home/arcolife/workspace/projects/CasperLabs/forks/CasperLabs/targets.yml || true
}

cleanup() {
    echo "Cleanup"
    # stop_prometheus
    stop_engine
    echo "Cleaned"
}

# run_prometheus
run_engine

# set +u
run_node_sbt
