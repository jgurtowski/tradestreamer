FROM public.ecr.aws/amazonlinux/amazonlinux:latest

COPY install /install

RUN yum -y update && \
    yum -y install \
    java-11-amazon-corretto-headless \
    gzip \
    tar 

RUN bash /install/linux-install-1.10.3.855.sh

COPY src /tradestreamer/src
COPY deps.edn /tradestreamer

WORKDIR /tradestreamer
CMD clojure -m main
