/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package software.wings.helpers.ext.k8s.request;

import static io.harness.annotations.dev.HarnessTeam.CDP;

import io.harness.annotations.dev.HarnessModule;
import io.harness.annotations.dev.OwnedBy;
import io.harness.annotations.dev.TargetModule;
import io.harness.delegate.task.k8s.K8sTaskType;
import io.harness.k8s.model.HelmVersion;

import java.util.Set;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TargetModule(HarnessModule._950_DELEGATE_TASKS_BEANS)
@OwnedBy(CDP)
public class K8sInstanceSyncTaskParameters extends K8sTaskParameters {
  String namespace;
  @Builder
  public K8sInstanceSyncTaskParameters(String accountId, String appId, String commandName, String activityId,
      K8sClusterConfig k8sClusterConfig, String workflowExecutionId, String releaseName, Integer timeoutIntervalInMin,
      String namespace, HelmVersion helmVersion, Set<String> delegateSelectors, boolean useLatestChartMuseumVersion,
      boolean useVarSupportForKustomize, boolean useNewKubectlVersion) {
    super(accountId, appId, commandName, activityId, k8sClusterConfig, workflowExecutionId, releaseName,
        timeoutIntervalInMin, K8sTaskType.INSTANCE_SYNC, helmVersion, delegateSelectors, useLatestChartMuseumVersion,
        useVarSupportForKustomize, useNewKubectlVersion);
    this.namespace = namespace;
  }
}
