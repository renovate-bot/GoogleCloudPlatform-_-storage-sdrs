/*
 * Copyright 2019 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and limitations under the License.
 *
 * Any software provided by Google hereunder is distributed “AS IS”,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, and is not intended for production use.
 */

package com.google.gcs.sdrs.service.impl;

import com.google.gcs.sdrs.JobManager.JobManager;
import com.google.gcs.sdrs.controller.filter.UserInfo;
import com.google.gcs.sdrs.controller.pojo.RetentionRuleCreateRequest;
import com.google.gcs.sdrs.controller.pojo.RetentionRuleResponse;
import com.google.gcs.sdrs.controller.pojo.RetentionRuleUpdateRequest;
import com.google.gcs.sdrs.dao.impl.RetentionRuleDaoImpl;
import com.google.gcs.sdrs.dao.model.RetentionRule;
import com.google.gcs.sdrs.enums.RetentionRuleType;
import com.google.gcs.sdrs.worker.Worker;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RetentionRulesServiceImplTest {

  private RetentionRulesServiceImpl service = new RetentionRulesServiceImpl();
  private RetentionRule globalRule;
  private List<String> projectIds = new ArrayList<>();

  @Before
  public void setup() {
    service.ruleDao = mock(RetentionRuleDaoImpl.class);
    service.jobManager = mock(JobManager.class);
    globalRule = new RetentionRule();
    globalRule.setId(10);
    globalRule.setProjectId("global-default");
    globalRule.setDataStorageName("global");
    globalRule.setRetentionPeriodInDays(365);
    String projectId = "test";
    projectIds.add(projectId);
  }

  @Test
  public void createRulePersistsDatasetEntity() throws SQLException {
    RetentionRuleCreateRequest createRule = new RetentionRuleCreateRequest();
    createRule.setRetentionRuleType(RetentionRuleType.DATASET);
    createRule.setRetentionPeriod(123);
    createRule.setDatasetName("dataset");
    createRule.setDataStorageName("gs://b/d");
    createRule.setProjectId("projectId");

    when(service.ruleDao.findGlobalRuleByProjectId(any())).thenReturn(globalRule);
    when(service.ruleDao.getAllDatasetRuleProjectIds()).thenReturn(projectIds);
    when(service.ruleDao.save(any())).thenReturn(1);

    service.createRetentionRule(createRule, new UserInfo());

    ArgumentCaptor<RetentionRule> captor = ArgumentCaptor.forClass(RetentionRule.class);
    ArgumentCaptor<Worker> workerCaptor = ArgumentCaptor.forClass(Worker.class);

    verify(service.ruleDao).save(captor.capture());
    verify(service.jobManager).submitJob(workerCaptor.capture());
    RetentionRule input = captor.getValue();
    assertEquals(1, (int) input.getId());
    assertEquals(RetentionRuleType.DATASET, input.getType());
    assertEquals(123, (int) input.getRetentionPeriodInDays());
    assertEquals(true, input.getIsActive());
    assertEquals("projectId", input.getProjectId());
    assertEquals("gs://b/d", input.getDataStorageName());
    assertEquals("dataset", input.getDatasetName());
    assertEquals(1, (int) input.getVersion());
  }

  @Test
  public void createRuleUsesBucketForDatasetWhenNoDataset() throws SQLException {
    RetentionRuleCreateRequest createRule = new RetentionRuleCreateRequest();
    createRule.setRetentionRuleType(RetentionRuleType.DATASET);
    createRule.setRetentionPeriod(123);
    createRule.setDataStorageName("gs://b");
    createRule.setProjectId("projectId");

    when(service.ruleDao.save(any())).thenReturn(1);

    service.createRetentionRule(createRule, new UserInfo());

    ArgumentCaptor<RetentionRule> captor = ArgumentCaptor.forClass(RetentionRule.class);

    verify(service.ruleDao).save(captor.capture());
    RetentionRule input = captor.getValue();
    assertEquals("gs://b", input.getDataStorageName());
    assertEquals("b", input.getDatasetName());
  }

  @Test
  public void createRuleUsesDataStorageDatasetForDataset() throws SQLException {
    RetentionRuleCreateRequest createRule = new RetentionRuleCreateRequest();
    createRule.setRetentionRuleType(RetentionRuleType.DATASET);
    createRule.setRetentionPeriod(123);
    createRule.setDataStorageName("gs://b/d");
    createRule.setProjectId("projectId");

    when(service.ruleDao.save(any())).thenReturn(1);

    service.createRetentionRule(createRule, new UserInfo());

    ArgumentCaptor<RetentionRule> captor = ArgumentCaptor.forClass(RetentionRule.class);

    verify(service.ruleDao).save(captor.capture());
    RetentionRule input = captor.getValue();
    assertEquals("gs://b/d", input.getDataStorageName());
    assertEquals("d", input.getDatasetName());
  }

  @Test
  public void createRulePersistsGlobalEntity() throws SQLException {
    RetentionRuleCreateRequest createRule = new RetentionRuleCreateRequest();
    createRule.setRetentionRuleType(RetentionRuleType.GLOBAL);
    createRule.setRetentionPeriod(123);

    when(service.ruleDao.findGlobalRuleByProjectId(any())).thenReturn(globalRule);
    when(service.ruleDao.getAllDatasetRuleProjectIds()).thenReturn(projectIds);
    when(service.ruleDao.save(any())).thenReturn(1);

    service.createRetentionRule(createRule, new UserInfo());

    ArgumentCaptor<RetentionRule> captor = ArgumentCaptor.forClass(RetentionRule.class);
    ArgumentCaptor<Worker> workerCaptor = ArgumentCaptor.forClass(Worker.class);

    verify(service.ruleDao).save(captor.capture());
    verify(service.jobManager).submitJob(workerCaptor.capture());
    RetentionRule input = captor.getValue();
    assertEquals(1, (int) input.getId());
    assertEquals(RetentionRuleType.GLOBAL, input.getType());
    assertEquals(123, (int) input.getRetentionPeriodInDays());
    assertEquals(true, input.getIsActive());
    assertEquals("global-default", input.getProjectId());
    assertEquals(1, (int) input.getVersion());
    assertEquals("global", input.getDataStorageName());
    assertNull(input.getDatasetName());
  }

  @Test
  public void updateDatasetRuleFetchesAndUpdatesEntity() throws SQLException {
    RetentionRuleUpdateRequest request = new RetentionRuleUpdateRequest();
    request.setRetentionPeriod(123);
    RetentionRule existingRule = new RetentionRule();
    existingRule.setId(2);
    existingRule.setRetentionPeriodInDays(12);
    existingRule.setVersion(3);
    existingRule.setType(RetentionRuleType.DATASET);
    when(service.ruleDao.findById(2)).thenReturn(existingRule);

    RetentionRuleResponse result = service.updateRetentionRule(2, request);

    ArgumentCaptor<RetentionRule> captor = ArgumentCaptor.forClass(RetentionRule.class);
    verify(service.ruleDao).update(captor.capture());
    RetentionRule input = captor.getValue();
    assertEquals(4, (int) input.getVersion());
    assertEquals(RetentionRuleType.DATASET, result.getType());
    assertEquals(2, (int) result.getRuleId());
    assertEquals(123, (int) result.getRetentionPeriod());
  }

  @Test
  public void updateGlobalRuleFetchesAndUpdatesEntity() throws SQLException {
    RetentionRuleUpdateRequest request = new RetentionRuleUpdateRequest();
    request.setRetentionPeriod(123);
    RetentionRule existingRule = new RetentionRule();
    existingRule.setId(2);
    existingRule.setRetentionPeriodInDays(12);
    existingRule.setVersion(3);
    existingRule.setType(RetentionRuleType.GLOBAL);
    when(service.ruleDao.findById(2)).thenReturn(existingRule);
    when(service.ruleDao.getAllDatasetRuleProjectIds()).thenReturn(projectIds);

    RetentionRuleResponse result = service.updateRetentionRule(2, request);

    ArgumentCaptor<RetentionRule> captor = ArgumentCaptor.forClass(RetentionRule.class);
    ArgumentCaptor<Worker> workerCaptor = ArgumentCaptor.forClass(Worker.class);

    verify(service.ruleDao).update(captor.capture());
    verify(service.jobManager).submitJob(workerCaptor.capture());
    RetentionRule input = captor.getValue();
    assertEquals(4, (int) input.getVersion());
    assertEquals(RetentionRuleType.GLOBAL, result.getType());
    assertEquals(2, (int) result.getRuleId());
    assertEquals(123, (int) result.getRetentionPeriod());
  }
}
