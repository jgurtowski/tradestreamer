![arc](assets/tradestream.png)

Design Doc: https://github.com/jgurtowski/tradestreamer/blob/master/project.org

Pull streaming quotes from tradier, compress with bzip2, push to s3

src/ - Clojure source code
infrastructure/ - aws infrastructure w/ cdk. deploy with npm run build && cdk deploy

Code pipelines uses the build.yml file in the root directory to build a docker image which is
stored in ECR. The image is run every day at 9:15 via fargate. 