/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.service.impl;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;

import software.wings.beans.SettingAttribute;

@OwnedBy(HarnessTeam.CDC)
public interface CloudProviderObserver {
  void onSaved(SettingAttribute settingAttribute);

  void onUpdated(SettingAttribute prevSettingAttribute, SettingAttribute currSettingAttribute);

  void onDeleted(SettingAttribute settingAttribute);
}
