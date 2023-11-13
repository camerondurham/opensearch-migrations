import {Stack} from "aws-cdk-lib";
import {StackPropsExt} from "../stack-composer";
import {
  BastionHostLinux,
  BlockDeviceVolume,
  MachineImage,
  Peer,
  Port,
  SecurityGroup,
  IVpc,
} from "aws-cdk-lib/aws-ec2";
import {MountPoint, PortMapping, Protocol, ServiceConnectService, Volume} from "aws-cdk-lib/aws-ecs";
import {Construct} from "constructs";
import {join} from "path";
import {MigrationServiceCore} from "./migration-service-core";
import {StringParameter} from "aws-cdk-lib/aws-ssm";
import {OpenSearchDomainStack} from "../opensearch-domain-stack";
import {EngineVersion} from "aws-cdk-lib/aws-opensearchservice";
import {EbsDeviceVolumeType} from "aws-cdk-lib/aws-ec2";
import {AnyPrincipal, Effect, PolicyStatement} from "aws-cdk-lib/aws-iam";

export interface MigrationAnalyticsProps extends StackPropsExt {
    readonly vpc: IVpc,
    readonly vpcSubnetIds?: string[],
    readonly vpcSecurityGroupIds?: string[],
    readonly availabilityZoneCount?: number,

    readonly extraArgs?: string,
    readonly engineVersion: EngineVersion,
    readonly dataNodeInstanceType?: string,
    readonly dataNodes?: number,
    readonly dedicatedManagerNodeType?: string,
    readonly dedicatedManagerNodeCount?: number,
    readonly warmInstanceType?: string,
    readonly warmNodes?: number,
    readonly enforceHTTPS?: boolean,
    readonly ebsEnabled?: boolean,
    readonly ebsIops?: number,
    readonly ebsVolumeSize?: number,
    readonly ebsVolumeType?: EbsDeviceVolumeType,
    readonly encryptionAtRestEnabled?: boolean,
    readonly encryptionAtRestKmsKeyARN?: string,
    readonly appLogEnabled?: boolean,
    readonly appLogGroup?: string,
    readonly nodeToNodeEncryptionEnabled?: boolean,
}

const domainName = "migration-analytics-domain"

// The MigrationAnalyticsStack consists of the OpenTelemetry Collector ECS container & an
// OpenSearch cluster with dashboard.
export class MigrationAnalyticsStack extends MigrationServiceCore {

    // openSearchAnalyticsStack: Stack

    constructor(scope: Construct, id: string, props: MigrationAnalyticsProps) {
        super(scope, id, props)

        // Bastion Security Group
        const bastionSecurityGroup = new SecurityGroup(
          this,
          "analyticsDashboardBastionSecurityGroup",
          {
            vpc: props.vpc,
            allowAllOutbound: true,
            securityGroupName: "analyticsDashboardBastionSecurityGroup",
          }
        );
        let securityGroups = [
            SecurityGroup.fromSecurityGroupId(this, "serviceConnectSG", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/serviceConnectSecurityGroupId`)),
            SecurityGroup.fromSecurityGroupId(this, "migrationAnalyticsSGId", StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/analyticsDomainSGId`)),
            bastionSecurityGroup
        ]
        securityGroups[1].addIngressRule(bastionSecurityGroup, Port.tcp(443))

        // Bastion host to access Opensearch Dashboards
        new BastionHostLinux(this, "AnalyticsDashboardBastionHost", {
          vpc: props.vpc,
          securityGroup: bastionSecurityGroup,
          machineImage: MachineImage.latestAmazonLinux2023(),
          blockDevices: [
            {
              deviceName: "/dev/xvda",
              volume: BlockDeviceVolume.ebs(10, {
                encrypted: true,
              }),
            },
          ],
        });

        // Port Mappings for collector and health check
        const otelCollectorPort: PortMapping = {
          name: "otel-collector-connect",
          hostPort: 4317,
          containerPort: 4317,
          protocol: Protocol.TCP
        }
        const otelHealthCheckPort: PortMapping = {
          name: "otel-healthcheck-connect",
          hostPort: 13133,
          containerPort: 13133,
          protocol: Protocol.TCP
        }
        const serviceConnectServiceCollector: ServiceConnectService = {
          portMappingName: "otel-collector-connect",
          port: 4317,
          dnsName: "otel-collector"
        }
        const serviceConnectServiceHealthCheck: ServiceConnectService = {
          portMappingName: "otel-healthcheck-connect",
          port: 13133,
          dnsName: "otel-healthcheck"
        }

        const analyticsDomainEndpoint = StringParameter.valueForStringParameter(this, `/migration/${props.stage}/${props.defaultDeployId}/analyticsDomainEndpoint`)

        this.createService({
            serviceName: `otel-collector`,
            dockerFilePath: join(__dirname, "../../../../../", "TrafficCapture/dockerSolution/src/main/docker/otelcol"),
            securityGroups: securityGroups,
            taskCpuUnits: 1024,
            taskMemoryLimitMiB: 4096,
            portMappings: [otelCollectorPort, otelHealthCheckPort],
            serviceConnectServices: [serviceConnectServiceCollector, serviceConnectServiceHealthCheck],
            environment: {
              "ANALYTICS_DOMAIN_ENDPOINT": analyticsDomainEndpoint
            },
            ...props
        });


    }

}