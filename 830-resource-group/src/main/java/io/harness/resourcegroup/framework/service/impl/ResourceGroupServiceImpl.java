/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.resourcegroup.framework.service.impl;

import static io.harness.annotations.dev.HarnessTeam.PL;
import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;
import static io.harness.exception.WingsException.USER_SRE;
import static io.harness.outbox.TransactionOutboxModule.OUTBOX_TRANSACTION_TEMPLATE;
import static io.harness.springdata.TransactionUtils.DEFAULT_TRANSACTION_RETRY_POLICY;
import static io.harness.utils.PageUtils.getPageRequest;

import static java.lang.Boolean.TRUE;

import io.harness.annotations.dev.OwnedBy;
import io.harness.beans.Scope;
import io.harness.beans.ScopeLevel;
import io.harness.beans.SortOrder;
import io.harness.exception.DuplicateFieldException;
import io.harness.exception.InvalidRequestException;
import io.harness.ng.beans.PageRequest;
import io.harness.ng.core.common.beans.NGTag.NGTagKeys;
import io.harness.outbox.api.OutboxService;
import io.harness.remote.NGObjectMapperHelper;
import io.harness.resourcegroup.framework.events.ResourceGroupCreateEvent;
import io.harness.resourcegroup.framework.events.ResourceGroupDeleteEvent;
import io.harness.resourcegroup.framework.events.ResourceGroupUpdateEvent;
import io.harness.resourcegroup.framework.remote.mapper.ResourceGroupMapper;
import io.harness.resourcegroup.framework.repositories.spring.ResourceGroupRepository;
import io.harness.resourcegroup.framework.service.ResourceGroupService;
import io.harness.resourcegroup.model.ResourceGroup;
import io.harness.resourcegroup.model.ResourceGroup.ResourceGroupKeys;
import io.harness.resourcegroup.model.StaticResourceSelector.StaticResourceSelectorKeys;
import io.harness.resourcegroup.remote.dto.ManagedFilter;
import io.harness.resourcegroup.remote.dto.ResourceGroupDTO;
import io.harness.resourcegroup.remote.dto.ResourceGroupFilterDTO;
import io.harness.resourcegroupclient.ResourceGroupResponse;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.validation.executable.ValidateOnExecution;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.transaction.support.TransactionTemplate;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Slf4j
@OwnedBy(PL)
@ValidateOnExecution
public class ResourceGroupServiceImpl implements ResourceGroupService {
  ResourceGroupValidatorServiceImpl resourceGroupValidatorService;
  ResourceGroupRepository resourceGroupRepository;
  OutboxService outboxService;
  TransactionTemplate transactionTemplate;

  @Inject
  public ResourceGroupServiceImpl(ResourceGroupValidatorServiceImpl resourceGroupValidatorService,
      ResourceGroupRepository resourceGroupRepository, OutboxService outboxService,
      @Named(OUTBOX_TRANSACTION_TEMPLATE) TransactionTemplate transactionTemplate) {
    this.resourceGroupValidatorService = resourceGroupValidatorService;
    this.resourceGroupRepository = resourceGroupRepository;
    this.outboxService = outboxService;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public ResourceGroupResponse create(ResourceGroupDTO resourceGroupDTO, boolean harnessManaged) {
    ResourceGroup resourceGroup = ResourceGroupMapper.fromDTO(resourceGroupDTO);
    resourceGroup.setHarnessManaged(harnessManaged);
    return ResourceGroupMapper.toResponseWrapper(create(resourceGroup));
  }

  private ResourceGroup create(ResourceGroup resourceGroup) {
    try {
      return createInternal(resourceGroup);
    } catch (DuplicateKeyException ex) {
      throw new DuplicateFieldException(
          String.format("A resource group with identifier %s already exists at the specified scope",
              resourceGroup.getIdentifier()),
          USER_SRE, ex);
    }
  }

  private ResourceGroup createInternal(ResourceGroup resourceGroup) {
    boolean sanitized = resourceGroupValidatorService.sanitizeResourceSelectors(resourceGroup);
    if (sanitized && resourceGroup.getResourceSelectors().isEmpty()) {
      throw new InvalidRequestException("All selected resources are invalid");
    }
    return Failsafe.with(DEFAULT_TRANSACTION_RETRY_POLICY).get(() -> transactionTemplate.execute(status -> {
      ResourceGroup savedResourceGroup = resourceGroupRepository.save(resourceGroup);
      outboxService.save(new ResourceGroupCreateEvent(
          savedResourceGroup.getAccountIdentifier(), ResourceGroupMapper.toDTO(savedResourceGroup)));
      return savedResourceGroup;
    }));
  }

  @Override
  public Page<ResourceGroupResponse> list(Scope scope, PageRequest pageRequest, String searchTerm) {
    if (isEmpty(pageRequest.getSortOrders())) {
      SortOrder harnessManagedOrder =
          SortOrder.Builder.aSortOrder().withField(ResourceGroupKeys.harnessManaged, SortOrder.OrderType.DESC).build();
      SortOrder lastModifiedOrder =
          SortOrder.Builder.aSortOrder().withField(ResourceGroupKeys.lastModifiedAt, SortOrder.OrderType.DESC).build();
      pageRequest.setSortOrders(ImmutableList.of(harnessManagedOrder, lastModifiedOrder));
    }
    Pageable page = getPageRequest(pageRequest);
    ResourceGroupFilterDTO resourceGroupFilterDTO = ResourceGroupFilterDTO.builder()
                                                        .accountIdentifier(scope.getAccountIdentifier())
                                                        .orgIdentifier(scope.getOrgIdentifier())
                                                        .projectIdentifier(scope.getProjectIdentifier())
                                                        .searchTerm(searchTerm)
                                                        .build();
    Criteria criteria = getResourceGroupFilterCriteria(resourceGroupFilterDTO);
    return resourceGroupRepository.findAll(criteria, page).map(ResourceGroupMapper::toResponseWrapper);
  }

  @Override
  public Page<ResourceGroupResponse> list(ResourceGroupFilterDTO resourceGroupFilterDTO, PageRequest pageRequest) {
    Criteria criteria = getResourceGroupFilterCriteria(resourceGroupFilterDTO);
    return resourceGroupRepository.findAll(criteria, getPageRequest(pageRequest))
        .map(ResourceGroupMapper::toResponseWrapper);
  }

  private Criteria getResourceGroupFilterCriteria(ResourceGroupFilterDTO resourceGroupFilterDTO) {
    Criteria criteria = new Criteria();
    if (isNotEmpty(resourceGroupFilterDTO.getIdentifierFilter())) {
      criteria.and(ResourceGroupKeys.identifier).in(resourceGroupFilterDTO.getIdentifierFilter());
    }
    Criteria scopeCriteria = getBaseScopeCriteria(resourceGroupFilterDTO.getAccountIdentifier(),
        resourceGroupFilterDTO.getOrgIdentifier(), resourceGroupFilterDTO.getProjectIdentifier())
                                 .and(ResourceGroupKeys.harnessManaged)
                                 .ne(true);
    Criteria managedCriteria = getBaseScopeCriteria(null, null, null).and(ResourceGroupKeys.harnessManaged).is(true);

    if (isNotEmpty(resourceGroupFilterDTO.getAccountIdentifier())) {
      managedCriteria.and(ResourceGroupKeys.allowedScopeLevels)
          .is(ScopeLevel
                  .of(resourceGroupFilterDTO.getAccountIdentifier(), resourceGroupFilterDTO.getOrgIdentifier(),
                      resourceGroupFilterDTO.getProjectIdentifier())
                  .toString()
                  .toLowerCase());
    } else if (isNotEmpty(resourceGroupFilterDTO.getScopeLevelFilter())) {
      criteria.and(ResourceGroupKeys.allowedScopeLevels).in(resourceGroupFilterDTO.getScopeLevelFilter());
    }

    List<Criteria> andOperatorCriteriaList = new ArrayList<>();

    if (ManagedFilter.ONLY_MANAGED.equals(resourceGroupFilterDTO.getManagedFilter())) {
      andOperatorCriteriaList.add(managedCriteria);
    } else if (ManagedFilter.ONLY_CUSTOM.equals(resourceGroupFilterDTO.getManagedFilter())) {
      andOperatorCriteriaList.add(scopeCriteria);
    } else {
      andOperatorCriteriaList.add(new Criteria().orOperator(scopeCriteria, managedCriteria));
    }

    if (isNotEmpty(resourceGroupFilterDTO.getSearchTerm())) {
      andOperatorCriteriaList.add(new Criteria().orOperator(
          Criteria.where(ResourceGroupKeys.name).regex(resourceGroupFilterDTO.getSearchTerm(), "i"),
          Criteria.where(ResourceGroupKeys.identifier).regex(resourceGroupFilterDTO.getSearchTerm(), "i"),
          Criteria.where(ResourceGroupKeys.tags + "." + NGTagKeys.key)
              .regex(resourceGroupFilterDTO.getSearchTerm(), "i"),
          Criteria.where(ResourceGroupKeys.tags + "." + NGTagKeys.value)
              .regex(resourceGroupFilterDTO.getSearchTerm(), "i")));
    }

    if (isNotEmpty(resourceGroupFilterDTO.getResourceSelectorFilterList())) {
      List<Criteria> resourceSelectorCriteria = new ArrayList<>();
      resourceGroupFilterDTO.getResourceSelectorFilterList().forEach(resourceSelectorFilter
          -> resourceSelectorCriteria.add(Criteria.where(ResourceGroupKeys.resourceSelectors)
                                              .elemMatch(Criteria.where(StaticResourceSelectorKeys.resourceType)
                                                             .is(resourceSelectorFilter.getResourceType())
                                                             .and(StaticResourceSelectorKeys.identifiers)
                                                             .is(resourceSelectorFilter.getResourceIdentifier()))));
      andOperatorCriteriaList.add(new Criteria().orOperator(resourceSelectorCriteria.toArray(new Criteria[0])));
    }

    criteria.andOperator(andOperatorCriteriaList.toArray(new Criteria[0]));

    return criteria;
  }

  @Override
  public boolean delete(Scope scope, String identifier) {
    Optional<ResourceGroup> resourceGroupOpt = getResourceGroup(scope, identifier, ManagedFilter.ONLY_CUSTOM);
    if (!resourceGroupOpt.isPresent()) {
      return false;
    }

    ResourceGroup resourceGroup = resourceGroupOpt.get();
    if (Boolean.TRUE.equals(resourceGroup.getHarnessManaged())) {
      throw new InvalidRequestException("Managed resource group cannot be deleted");
    }

    return Failsafe.with(DEFAULT_TRANSACTION_RETRY_POLICY).get(() -> transactionTemplate.execute(status -> {
      resourceGroupRepository.delete(resourceGroup);
      outboxService.save(
          new ResourceGroupDeleteEvent(scope.getAccountIdentifier(), ResourceGroupMapper.toDTO(resourceGroup)));
      return true;
    }));
  }

  @Override
  public void deleteManaged(String identifier) {
    Optional<ResourceGroup> resourceGroupOpt = getResourceGroup(null, identifier, ManagedFilter.ONLY_MANAGED);
    if (!resourceGroupOpt.isPresent()) {
      return;
    }
    ResourceGroup resourceGroup = resourceGroupOpt.get();

    Failsafe.with(DEFAULT_TRANSACTION_RETRY_POLICY).get(() -> transactionTemplate.execute(status -> {
      resourceGroupRepository.delete(resourceGroup);
      outboxService.save(new ResourceGroupDeleteEvent(null, ResourceGroupMapper.toDTO(resourceGroup)));
      return true;
    }));
  }

  @Override
  public void deleteByScope(Scope scope) {
    if (scope == null || isEmpty(scope.getAccountIdentifier())) {
      throw new InvalidRequestException("Invalid scope. Cannot proceed with deletion.");
    }
    Failsafe.with(DEFAULT_TRANSACTION_RETRY_POLICY).get(() -> transactionTemplate.execute(status -> {
      List<ResourceGroup> deletedResourceGroups =
          resourceGroupRepository.deleteByAccountIdentifierAndOrgIdentifierAndProjectIdentifier(
              scope.getAccountIdentifier(), scope.getOrgIdentifier(), scope.getProjectIdentifier());
      if (isNotEmpty(deletedResourceGroups)) {
        deletedResourceGroups.forEach(rg
            -> outboxService.save(
                new ResourceGroupDeleteEvent(rg.getAccountIdentifier(), ResourceGroupMapper.toDTO(rg))));
      }
      return true;
    }));
  }

  @Override
  @SuppressWarnings("PMD")
  public Optional<ResourceGroupResponse> get(Scope scope, String identifier, ManagedFilter managedFilter) {
    Optional<ResourceGroup> resourceGroupOpt = getResourceGroup(scope, identifier, managedFilter);
    return Optional.ofNullable(ResourceGroupMapper.toResponseWrapper(resourceGroupOpt.orElse(null)));
  }

  private Optional<ResourceGroup> getResourceGroup(Scope scope, String identifier, ManagedFilter managedFilter) {
    Criteria criteria = new Criteria();
    criteria.and(ResourceGroupKeys.identifier).is(identifier);
    if ((scope == null || isEmpty(scope.getAccountIdentifier())) && !ManagedFilter.ONLY_MANAGED.equals(managedFilter)) {
      throw new InvalidRequestException(
          "Either managed filter should be set to only managed, or scope filter should be non-empty");
    }

    Criteria managedCriteria = getBaseScopeCriteria(null, null, null).and(ResourceGroupKeys.harnessManaged).is(true);

    if (ManagedFilter.ONLY_MANAGED.equals(managedFilter)) {
      if (scope != null && isNotEmpty(scope.getAccountIdentifier())) {
        managedCriteria.and(ResourceGroupKeys.allowedScopeLevels).is(ScopeLevel.of(scope).toString().toLowerCase());
      }
      criteria.andOperator(managedCriteria);
    } else if (ManagedFilter.ONLY_CUSTOM.equals(managedFilter)) {
      criteria.andOperator(
          getBaseScopeCriteria(scope.getAccountIdentifier(), scope.getOrgIdentifier(), scope.getProjectIdentifier())
              .and(ResourceGroupKeys.harnessManaged)
              .ne(true));
    } else {
      managedCriteria.and(ResourceGroupKeys.allowedScopeLevels).is(ScopeLevel.of(scope).toString().toLowerCase());
      criteria.orOperator(
          getBaseScopeCriteria(scope.getAccountIdentifier(), scope.getOrgIdentifier(), scope.getProjectIdentifier())
              .and(ResourceGroupKeys.harnessManaged)
              .ne(true),
          managedCriteria);
    }

    return resourceGroupRepository.find(criteria);
  }

  private Criteria getBaseScopeCriteria(String accountIdentifier, String orgIdentifier, String projectIdentifier) {
    return Criteria.where(ResourceGroupKeys.accountIdentifier)
        .is(accountIdentifier)
        .and(ResourceGroupKeys.orgIdentifier)
        .is(orgIdentifier)
        .and(ResourceGroupKeys.projectIdentifier)
        .is(projectIdentifier);
  }

  @Override
  public Optional<ResourceGroupResponse> update(
      ResourceGroupDTO resourceGroupDTO, boolean sanitizeResourceSelectors, boolean harnessManaged) {
    ManagedFilter managedFilter = harnessManaged ? ManagedFilter.ONLY_MANAGED : ManagedFilter.ONLY_CUSTOM;
    Optional<ResourceGroup> resourceGroupOpt =
        getResourceGroup(Scope.of(resourceGroupDTO.getAccountIdentifier(), resourceGroupDTO.getOrgIdentifier(),
                             resourceGroupDTO.getProjectIdentifier()),
            resourceGroupDTO.getIdentifier(), managedFilter);
    if (!resourceGroupOpt.isPresent()) {
      throw new InvalidRequestException(
          String.format("Resource group with Identifier [{%s}] in Scope {%s} does not exist",
              resourceGroupDTO.getIdentifier(), resourceGroupDTO.getScope()));
    }
    ResourceGroup updatedResourceGroup = ResourceGroupMapper.fromDTO(resourceGroupDTO);
    if (sanitizeResourceSelectors) {
      resourceGroupValidatorService.sanitizeResourceSelectors(updatedResourceGroup);
    }
    ResourceGroup savedResourceGroup = resourceGroupOpt.get();
    if (savedResourceGroup.getHarnessManaged().equals(TRUE) && !harnessManaged) {
      throw new InvalidRequestException("Can't update managed resource group");
    }

    ResourceGroupDTO oldResourceGroup =
        (ResourceGroupDTO) NGObjectMapperHelper.clone(ResourceGroupMapper.toDTO(savedResourceGroup));

    savedResourceGroup.setName(updatedResourceGroup.getName());
    savedResourceGroup.setColor(updatedResourceGroup.getColor());
    savedResourceGroup.setTags(updatedResourceGroup.getTags());
    savedResourceGroup.setDescription(updatedResourceGroup.getDescription());
    savedResourceGroup.setFullScopeSelected(Boolean.TRUE.equals(updatedResourceGroup.getFullScopeSelected()));
    savedResourceGroup.setResourceSelectors(updatedResourceGroup.getResourceSelectors());
    if (areScopeLevelsUpdated(savedResourceGroup, updatedResourceGroup) && !harnessManaged) {
      throw new InvalidRequestException("Cannot change the scopes at which this resource group can be used.");
    }
    savedResourceGroup.setAllowedScopeLevels(updatedResourceGroup.getAllowedScopeLevels());

    updatedResourceGroup =
        Failsafe.with(DEFAULT_TRANSACTION_RETRY_POLICY).get(() -> transactionTemplate.execute(status -> {
          ResourceGroup resourceGroup = resourceGroupRepository.save(savedResourceGroup);
          outboxService.save(new ResourceGroupUpdateEvent(
              savedResourceGroup.getAccountIdentifier(), ResourceGroupMapper.toDTO(resourceGroup), oldResourceGroup));
          return resourceGroup;
        }));
    return Optional.ofNullable(ResourceGroupMapper.toResponseWrapper(updatedResourceGroup));
  }

  private boolean areScopeLevelsUpdated(ResourceGroup currentResourceGroup, ResourceGroup resourceGroupUpdate) {
    if (isEmpty(currentResourceGroup.getAllowedScopeLevels())) {
      return false;
    }
    return !currentResourceGroup.getAllowedScopeLevels().equals(resourceGroupUpdate.getAllowedScopeLevels());
  }
}
