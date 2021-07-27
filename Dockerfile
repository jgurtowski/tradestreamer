FROM alpine:latest

COPY install /install

RUN apk update && \
    apk add \
    bash \
    curl \
    openjdk11-jre

RUN bash /install/linux-install-1.10.3.855.sh

COPY src /tradestreamer/src
COPY deps.edn /tradestreamer

WORKDIR /tradestreamer
CMD clojure -m main
