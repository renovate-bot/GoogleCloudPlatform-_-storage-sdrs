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
 *
 */

package com.google.gcs.sdrs.rule.impl;

import com.google.api.services.storagetransfer.v1.Storagetransfer;
import com.google.api.services.storagetransfer.v1.model.TransferJob;
import com.google.api.services.storagetransfer.v1.model.TransferSpec;
import com.google.gcs.sdrs.SdrsApplication;
import com.google.gcs.sdrs.dao.model.RetentionJob;
import com.google.gcs.sdrs.dao.model.RetentionRule;
import com.google.gcs.sdrs.enums.RetentionRuleType;
import com.google.gcs.sdrs.rule.RuleExecutor;
import com.google.gcs.sdrs.util.PrefixGeneratorUtility;
import com.google.gcs.sdrs.util.RetentionUtil;
import com.google.gcs.sdrs.util.StsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * An implementation of the Rule Executor interface that uses STS
 */
public class StsRuleExecutor implements RuleExecutor {

  public static StsRuleExecutor instance;
  private final String DEFAULT_SUFFIX = "shadow";
  private final String DEFAULT_PROJECT_ID = "global-default";
  private final String DEFAULT_MAX_PREFIX_COUNT = "1000";
  private final String DEFAULT_LOOKBACK_IN_DAYS = "365";
  private String suffix;
  private String defaultProjectId;
  private int maxPrefixCount;
  private int lookBackInDays;
  Storagetransfer client;

  private static final Logger logger = LoggerFactory.getLogger(StsRuleExecutor.class);

  public static StsRuleExecutor getInstance() {
    if (instance == null) {
      try {
        instance = new StsRuleExecutor();
      } catch (IOException ex) {
        logger.error("Could not establish connection with STS: ", ex.getMessage());
        logger.error("Underlying error: ", ex.getCause().getMessage());
      }
    }

    return instance;
  }

  /**
   * STS Rule Executor constructor that reads the bucket suffix from the configuration file
   * @throws IOException when the STS Client cannot be instantiated
   */
  private StsRuleExecutor() throws IOException {

    suffix = SdrsApplication.getAppConfigProperty("sts.suffix", DEFAULT_SUFFIX);
    maxPrefixCount = Integer.valueOf(SdrsApplication.getAppConfigProperty(
        "sts.maxPrefixCount",
        DEFAULT_MAX_PREFIX_COUNT));
    defaultProjectId = SdrsApplication.getAppConfigProperty(
        "sts.defaultProjectId",
        DEFAULT_PROJECT_ID);
    lookBackInDays = Integer.valueOf(SdrsApplication.getAppConfigProperty(
        "sts.maxLookBackInDays",
        DEFAULT_LOOKBACK_IN_DAYS));
  }

  /**
   * Executes a dataset retention rule
   * @param rule The {@link RetentionRule} to execute
   * @return A {@link RetentionJob} object
   * @throws IOException when communication can't be established with STS
   * @throws IllegalArgumentException when the rule is a global rule
   */
  @Override
  public RetentionJob executeDatasetRule(RetentionRule rule)
      throws IOException, IllegalArgumentException{

    if (rule.getType().equals(RetentionRuleType.GLOBAL)) {
      throw new IllegalArgumentException("GLOBAL retention rule type is invalid for this function");
    }

    ZonedDateTime zonedDateTimeNow = ZonedDateTime.now(Clock.systemUTC());

    List<String> prefixes = PrefixGeneratorUtility.generateTimePrefixes(
        RetentionUtil.getDatasetPath(rule.getDataStorageName()),
        zonedDateTimeNow.minusDays(lookBackInDays),
        zonedDateTimeNow.minusDays(rule.getRetentionPeriodInDays()));

    String projectId = rule.getProjectId();
    String sourceBucket = RetentionUtil.getBucketName(rule.getDataStorageName());
    String destinationBucket = RetentionUtil.getBucketName(rule.getDataStorageName(), suffix);

    String description = buildDescription(rule, zonedDateTimeNow);

    logger.debug(
        String.format("Creating STS job with projectId: %s, " +
                "description: %s, source: %s, destination: %s",
            projectId,
            description,
            sourceBucket,
            destinationBucket));

    TransferJob job =
        StsUtil.createStsJob(
            client,
            projectId,
            sourceBucket,
            destinationBucket,
            prefixes,
            description,
            zonedDateTimeNow);

    return buildRetentionJobEntity(job.getName(), rule);
  }

  /**
   * @param defaultRule the default rule to execute
   * @param bucketDatasetRules any dataset rules that exist within the same bucket
   *                             as the default rule
   * @param scheduledTime the recurring time at which you want the default rule to execute
   * @return A {@link Collection} of {@link RetentionJob} records
   */
  @Override
  public RetentionJob executeDefaultRule(
      RetentionRule defaultRule,
      Collection<RetentionRule> bucketDatasetRules,
      ZonedDateTime scheduledTime)
      throws IOException, IllegalArgumentException {

    if (defaultRule.getType().equals(RetentionRuleType.DATASET)) {
      String message = "DATASET retention rule type is invalid for this function";
      logger.error(message);
      throw new IllegalArgumentException(message);
    }

    List<String> prefixesToExclude = buildExcludePrefixList(bucketDatasetRules);
    String projectId = extractProjectId(defaultRule, bucketDatasetRules);
    String sourceBucket = RetentionUtil.getBucketName(defaultRule.getDataStorageName());
    String destinationBucket = RetentionUtil.getBucketName(defaultRule.getDataStorageName(), suffix);
    String description = buildDescription(defaultRule, scheduledTime);

    logger.debug(
        String.format("Creating STS job with for rule %s, projectId: %s, " +
                "description: %s, source: %s, destination: %s",
            defaultRule.getId(),
            projectId,
            description,
            sourceBucket,
            destinationBucket));

    TransferJob job =
        StsUtil.createDefaultStsJob(
            client,
            projectId,
            sourceBucket,
            destinationBucket,
            prefixesToExclude,
            description,
            scheduledTime,
            defaultRule.getRetentionPeriodInDays());

    return buildRetentionJobEntity(job.getName(), defaultRule);
  }

  /**
   * Sends a request to update a previously scheduled recurring transfer job
   * @param defaultJob The existing retention job record associated with the default rule
   * @param defaultRule the default rule record to update
   * @param bucketDatasetRules a {@link Collection} of child dataset rules
   * @return the {@link RetentionJob} record that was updated or
   * the original record if no update is required
   * @throws IOException if the {@link Storagetransfer} client can't establish a connection to STS
   * @throws IllegalArgumentException if the rule type is Dataset,
   * if no existing transfer job exists, if more than 1000 prefixes are excluded, or if the
   * projectId can't be determined
   */
  public RetentionJob updateDefaultRule(RetentionJob defaultJob,
                                        RetentionRule defaultRule,
                                        Collection<RetentionRule> bucketDatasetRules)
      throws IOException, IllegalArgumentException {

    if (defaultRule.getType().equals(RetentionRuleType.DATASET)) {
      String message = "DATASET retention rule type is invalid for this function";
      logger.error(message);
      throw new IllegalArgumentException(message);
    }

    // get the existing transfer job from STS
    TransferJob existingTransferJob = StsUtil.getExistingJob(
        client, defaultJob.getRetentionRuleProjectId(), defaultJob.getName());

    if (existingTransferJob == null) {
      String message = String.format(
          "Update failed. The requested transfer job %s does not exist in STS",
          defaultJob.getName());
      logger.error(message);
      throw new IllegalArgumentException(message);
    }
    // Get existing job from STS
    TransferSpec transferSpec = existingTransferJob.getTransferSpec();

    boolean retentionPeriodChanged = false;
    boolean prefixesToExcludeChanged = false;

    // Check if retention period changed
    String existingRetention = transferSpec.getObjectConditions()
        .getMinTimeElapsedSinceLastModification();
    String updatedRetention = StsUtil.convertRetentionInDaysToDuration(
        defaultRule.getRetentionPeriodInDays());

    if(!existingRetention.equals(updatedRetention)){
      transferSpec.getObjectConditions().setMinTimeElapsedSinceLastModification(updatedRetention);
      retentionPeriodChanged = true;
    }

    // check if prefixes to exclude changed
    List<String> existingExcludePrefixList = transferSpec
        .getObjectConditions().getExcludePrefixes();
    List<String> updatedPrefixesToExclude = buildExcludePrefixList(bucketDatasetRules);

    if (isSamePrefixList(existingExcludePrefixList, updatedPrefixesToExclude)) {
      transferSpec.getObjectConditions().setExcludePrefixes(updatedPrefixesToExclude);
      prefixesToExcludeChanged = true;
    }

    // only update if the retention period or prefix list has changed
    if (retentionPeriodChanged || prefixesToExcludeChanged) {
      //Build transfer job object
      TransferJob updatedJob = new TransferJob();
      updatedJob.setDescription(buildDescription(
          defaultRule,
          ZonedDateTime.now(Clock.systemUTC())));
      updatedJob.setTransferSpec(transferSpec);

      TransferJob returnedJob = StsUtil.updateExistingJob(client, updatedJob);
      return buildRetentionJobEntity(returnedJob.getName(), defaultRule);
    } else {
      return defaultJob;
    }
  }

  String buildDescription(RetentionRule rule, ZonedDateTime scheduledTime) {
    String description;
    if (rule.getId() == null && rule.getVersion() == null) {
      // a null id and version indicates a user triggered rule. Set description accordingly
      description = String.format("Rule User %s %s",
          rule.getDataStorageName(), scheduledTime.toString());
    } else {
      description = String.format("Rule %s %s %s",
          rule.getId(), rule.getVersion(), scheduledTime.toString());
    }

    return description;
  }



  boolean isSamePrefixList(List<String> oldList, List<String> newList) {
    if (oldList == null && newList == null) {
      return true;
    }

    if (oldList == null || newList == null) {
      return false;
    }

    if (oldList.size() != newList.size()) {
      return false;
    }

    Collections.sort(oldList);
    Collections.sort(newList);

    if (oldList.equals(newList)) {
      return true;
    }

    return false;
  }

  String extractProjectId(RetentionRule defaultRule, Collection<RetentionRule> datasetRules) {
    String projectId = defaultRule.getProjectId();
    // if the default rule doesn't have a projectId, get it from a child dataset rule
    if (defaultRule.getProjectId().isEmpty()
        || defaultRule.getProjectId().equalsIgnoreCase(defaultProjectId)) {
      Optional<RetentionRule> childRuleWithProject =
          datasetRules.stream().filter(r -> !r.getProjectId().isEmpty()).findFirst();
      if (childRuleWithProject.isPresent()) {
        projectId = childRuleWithProject.get().getProjectId();
      } else {
        String message = "STS job could not be created. No projectId found.";
        logger.error(message);
        throw new IllegalArgumentException(message);
      }
    }

    return projectId;
  }

  List<String> buildExcludePrefixList(Collection<RetentionRule> datasetRules)
      throws IllegalArgumentException {

    List<String> prefixesToExclude = new ArrayList<>();
    for (RetentionRule datasetRule : datasetRules) {
      // Adds the dataset folder to the exclude list as the retention is already being handled
      // by the dataset rule. No need to generate the full prefix here.
      String pathToExclude = RetentionUtil.getDatasetPath(datasetRule.getDataStorageName());
      if (!pathToExclude.isEmpty()) {
        prefixesToExclude.add(pathToExclude);
      }
    }

    // STS has a restriction of 1000 values in any prefix collection. This should never happen.
    if (prefixesToExclude.size() > maxPrefixCount) {
      String message = String.format(
          "There are too many dataset rules associated with this bucket. " +
              "A maximum of %s rules are allowed.", maxPrefixCount);
      logger.error(message);
      throw new IllegalArgumentException(message);
    }

    return  prefixesToExclude;
  }

  RetentionJob buildRetentionJobEntity(String jobName, RetentionRule rule) {
    RetentionJob retentionJob = new RetentionJob();
    retentionJob.setName(jobName);
    retentionJob.setRetentionRuleId(rule.getId());
    retentionJob.setRetentionRuleProjectId(rule.getProjectId());
    retentionJob.setRetentionRuleDataStorageName(rule.getDataStorageName());
    retentionJob.setRetentionRuleType(rule.getType());
    retentionJob.setRetentionRuleVersion(rule.getVersion());

    return retentionJob;
  }
}
