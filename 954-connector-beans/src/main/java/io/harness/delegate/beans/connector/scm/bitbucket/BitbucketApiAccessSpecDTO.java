/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.delegate.beans.connector.scm.bitbucket;

import io.harness.beans.DecryptableEntity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonSubTypes({
  @JsonSubTypes.
  Type(value = BitbucketUsernameTokenApiAccessDTO.class, name = BitbucketConnectorConstants.USERNAME_AND_TOKEN)
})
@Schema(name = "BitbucketApiAccess",
    description =
        "This contains details of the information such as references of username and password needed for Bitbucket API access")
public interface BitbucketApiAccessSpecDTO extends DecryptableEntity {}
