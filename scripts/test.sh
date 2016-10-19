#!/usr/bin/env bash
export GIT_COMMIT_SHORT=`git rev-parse --short HEAD`;
export DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )";

clean() {
  docker-compose -f $DIR/docker-compose.yml kill
}

trap 'clean' EXIT;

echo "Starting integration spec for $GIT_COMMIT_SHORT";

SONICD_CONTAINER=$(docker-compose -f $DIR/docker-compose.yml up -d);

if [ $? -ne 0 ]; then
  echo "exit status not 0: $SONICD_CONTAINER"
  exit 1
fi
echo "deployed sonicd container: $SONICD_CONTAINER. starting tests in 5s..";
sleep 5;

cd $DIR/../ && sbt test
