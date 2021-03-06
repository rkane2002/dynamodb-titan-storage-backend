/*
 * Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazon.titan.diskstorage.dynamodb.iterator;

import java.util.Iterator;
import java.util.concurrent.Callable;

import com.amazon.titan.diskstorage.dynamodb.BackendRuntimeException;
import com.amazon.titan.diskstorage.dynamodb.DynamoDBDelegate;
import com.amazon.titan.diskstorage.dynamodb.ExponentialBackoff.Scan;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.ScanResult;
import com.thinkaurelius.titan.diskstorage.BackendException;

/**
 * This class executes multiple scan requests on one segment of a table in series,
 * as a runnable. Instances meant to be used as tasks of the worker thread pool for parallel
 * scans.
 *
 * @author Alexander Patrikalakis
 *
 */
public class ScanSegmentWorker implements Callable<ScanContext>, Iterator<ScanResult> {
    private final DynamoDBDelegate delegate;
    private final ScanRequest request;
    private boolean hasNext;
    private int lastConsumedCapacity;

    public ScanSegmentWorker(final DynamoDBDelegate delegate, final ScanRequest request) {
        this.delegate = delegate;
        this.request = DynamoDBDelegate.copyScanRequest(request);
        this.hasNext = true;
        this.lastConsumedCapacity = delegate.estimateCapacityUnits(DynamoDBDelegate.SCAN, request.getTableName());
    }

    @Override
    public ScanResult next() {
        Scan backoff = new Scan(request, delegate, lastConsumedCapacity);
        ScanResult result = null;
        try {
            result = backoff.runWithBackoff(); //this will be non-null or runWithBackoff throws
        } catch (BackendException e) {
            throw new BackendRuntimeException(e);
        }

        if (result.getConsumedCapacity() != null) {
            lastConsumedCapacity = result.getConsumedCapacity().getCapacityUnits().intValue();
        }

        if (result.getLastEvaluatedKey() != null && !result.getLastEvaluatedKey().isEmpty()) {
            hasNext = true;
            request.setExclusiveStartKey(result.getLastEvaluatedKey());
        } else {
            hasNext = false;
        }

        return result;
    }

    @Override
    public boolean hasNext() {
        return hasNext;
    }

    @Override
    public ScanContext call() throws Exception {
        try {
            ScanRequest originalRequest = DynamoDBDelegate.copyScanRequest(request);
            ScanResult result = next();
            return new ScanContext(originalRequest, result);
        } catch (BackendRuntimeException e) {
            throw e.getBackendException();
        }
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("deletion not supported");
    }
}
