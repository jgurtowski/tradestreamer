import * as cdk from '@aws-cdk/core';
import * as codecommit from '@aws-cdk/aws-codecommit';
import * as ecr from '@aws-cdk/aws-ecr';
import * as ecspattern from '@aws-cdk/aws-ecs-patterns';
import * as ecs from '@aws-cdk/aws-ecs';
import * as codepipeline from '@aws-cdk/aws-codepipeline';
import * as cpactions from '@aws-cdk/aws-codepipeline-actions';
import * as codebuild from '@aws-cdk/aws-codebuild';
import * as s3 from '@aws-cdk/aws-s3';
import * as ec2 from '@aws-cdk/aws-ec2';
import * as secretsmanager from '@aws-cdk/aws-secretsmanager';
import * as iam from '@aws-cdk/aws-iam';
import { Schedule } from '@aws-cdk/aws-applicationautoscaling';

export class InfrastructureStack extends cdk.Stack {
    constructor(scope: cdk.Construct, id: string, props?: cdk.StackProps) {
        super(scope, id, props);



        const codeRepo = new codecommit.Repository(this, "TradeStreamerRepo", {
            repositoryName: "tradestreamer",
            description: "stream trades from tradier"
        });

        const codeCommitArtifact = new codepipeline.Artifact();

        const ecrRepo = new ecr.Repository(this, 'TradeStreamerECRRepo');

        const codeCommitSourceAction = new cpactions.CodeCommitSourceAction({
            actionName: 'CodeCommit',
            repository: codeRepo,
            output: codeCommitArtifact
        });

        const buildProject = new codebuild.PipelineProject(this, 'TradeStreamerBuildProject', {
            environment: {
                buildImage: codebuild.LinuxBuildImage.STANDARD_5_0,
                computeType: codebuild.ComputeType.SMALL,
                privileged: true
            },

        });

        const codeBuildAction = new cpactions.CodeBuildAction({
            actionName: 'TradeStreamerBuildImage',
            input: codeCommitArtifact,
            project: buildProject,
            environmentVariables: {
                "AWS_DEFAULT_REGION": { value: ecrRepo.env.region },
                "ECR_URI": { value: ecrRepo.repositoryUri },
                "IMAGE_NAME": { value: "tradestreamer" },
                "IMAGE_TAG": { value: "latest" }
            }
        });

        ecrRepo.grantPullPush(buildProject);

        new codepipeline.Pipeline(this, 'TradeStreamerPipeline', {
            pipelineName: 'TradeStreamerPipeline',
            stages: [{
                stageName: "SOURCE",
                actions: [codeCommitSourceAction]
            },
            {
                stageName: "BUILD",
                actions: [codeBuildAction]
            }]
        });


        const streamerVpc = new ec2.Vpc(this, 'TradeStreamerVPC', {
            natGateways: 0,
            cidr: "10.0.0.0/26",
            subnetConfiguration: [{
                name: "TradeStreamerFargatePublicSubnet",
                subnetType: ec2.SubnetType.PUBLIC,
            }]
        });

        const streamerCluster = new ecs.Cluster(this, 'TradeStreamerCluster', {
            clusterName: "TradeStreamerCluster",
            enableFargateCapacityProviders: true,
            vpc: streamerVpc,
        });


        const fargateTask = new ecspattern.ScheduledFargateTask(this, 'TradeStreamerFargateTask',
            {
                schedule: Schedule.cron({
                    weekDay: "MON-FRI",
                    hour: "13",
                    minute: "15"
                }),

                scheduledFargateTaskImageOptions: {
                    image: ecs.ContainerImage.fromEcrRepository(ecrRepo, "latest"),
                    cpu: 256,
                    memoryLimitMiB: 1024,
                },
                subnetSelection: {
                    subnetType: ec2.SubnetType.PUBLIC
                },
                cluster: streamerCluster,
            });

        const tradeDataBucket = s3.Bucket.fromBucketName(this, 'TradeDataBucket', "tradedata");

        tradeDataBucket.grantReadWrite(fargateTask.taskDefinition.executionRole!.grantPrincipal);
        tradeDataBucket.grantReadWrite(fargateTask.taskDefinition.taskRole.grantPrincipal);

        const tradierKeySecret = new secretsmanager.Secret(this, 'TradierAPIKeySecret', {
            secretName: 'TradierAPIKey'
        });
        tradierKeySecret.grantRead(fargateTask.taskDefinition.executionRole!.grantPrincipal);
        tradierKeySecret.grantRead(fargateTask.taskDefinition.taskRole.grantPrincipal);

        const jamesUser = iam.User.fromUserName(this, 'JamesUser', 'james');
        tradierKeySecret.grantRead(jamesUser);
    }
}
