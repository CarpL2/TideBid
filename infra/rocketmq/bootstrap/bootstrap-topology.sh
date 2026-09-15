#!/bin/sh

set -eu

NAMESRV_ADDR="${NAMESRV_ADDR:-rocketmq-nameserver:9876}"
CLUSTER_NAME="${ROCKETMQ_CLUSTER_NAME:-TideBidLocalCluster}"
MQADMIN="${ROCKETMQ_HOME:-/home/rocketmq/rocketmq-5.3.3}/bin/mqadmin"
MAX_ATTEMPTS=15

if [ ! -f "$MQADMIN" ]; then
    echo "RocketMQ mqadmin was not found at the expected image path." >&2
    exit 1
fi

run_with_retry() {
    description="$1"
    shift
    attempt=1
    while ! "$@"; do
        if [ "$attempt" -ge "$MAX_ATTEMPTS" ]; then
            echo "Failed to ${description} after ${MAX_ATTEMPTS} attempts." >&2
            return 1
        fi
        echo "Retrying ${description} (${attempt}/${MAX_ATTEMPTS})..." >&2
        attempt=$((attempt + 1))
        sleep 2
    done
}

upsert_normal_topic() {
    topic="$1"
    run_with_retry "create or update topic ${topic}" \
        sh "$MQADMIN" updateTopic \
        -n "$NAMESRV_ADDR" \
        -c "$CLUSTER_NAME" \
        -t "$topic" \
        -r 8 \
        -w 8 \
        -p 6
}

upsert_delay_topic() {
    topic="$1"
    run_with_retry "create or update delay topic ${topic}" \
        sh "$MQADMIN" updateTopic \
        -n "$NAMESRV_ADDR" \
        -c "$CLUSTER_NAME" \
        -t "$topic" \
        -r 8 \
        -w 8 \
        -p 6 \
        -a +message.type=DELAY
}

upsert_consumer_group() {
    group="$1"
    run_with_retry "create or update consumer group ${group}" \
        sh "$MQADMIN" updateSubGroup \
        -n "$NAMESRV_ADDR" \
        -c "$CLUSTER_NAME" \
        -g "$group" \
        -s true \
        -d false \
        -m false \
        -o false \
        -q 1 \
        -r 16 \
        -a true
}

upsert_normal_topic tidebid-auction-events
upsert_normal_topic tidebid-account-events
upsert_normal_topic tidebid-trade-events
upsert_delay_topic tidebid-scheduled-commands

upsert_consumer_group tidebid-auction-close-v1
upsert_consumer_group tidebid-account-deposit-v1
upsert_consumer_group tidebid-trade-auction-v1
upsert_consumer_group tidebid-trade-account-v1
upsert_consumer_group tidebid-trade-timeout-v1
upsert_consumer_group tidebid-account-credit-v1

echo "TideBid RocketMQ topology bootstrap completed successfully."
