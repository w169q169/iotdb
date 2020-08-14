/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.query.udf.manager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.io.FileUtils;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.StartupException;
import org.apache.iotdb.db.exception.runtime.StorageEngineFailureException;
import org.apache.iotdb.db.query.udf.datastructure.SerializableList.SerializationRecorder;
import org.apache.iotdb.db.service.IService;
import org.apache.iotdb.db.service.ServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TemporaryQueryDataFileManager implements IService {

  private static final Logger logger = LoggerFactory.getLogger(TemporaryQueryDataFileManager.class);

  private final Map<Long, Map<String, SerializationRecorder>> recorders;
  private final String temporaryFileDir;

  private TemporaryQueryDataFileManager() {
    recorders = new ConcurrentHashMap<>();
    temporaryFileDir =
        IoTDBDescriptor.getInstance().getConfig().getQueryDir()
            + File.separator + "udf" + File.separator + "tmp" + File.separator;
    makeDirIfNecessary(temporaryFileDir);
  }

  public RandomAccessFile register(SerializationRecorder recorder) throws FileNotFoundException {
    String dirName = getDirName(recorder.getQueryId(), recorder.getDataId());
    makeDirIfNecessary(dirName);
    String fileName = getFileName(dirName, recorder.getIndex());
    recorders.putIfAbsent(recorder.getQueryId(), new ConcurrentHashMap<>())
        .putIfAbsent(fileName, recorder);
    return new RandomAccessFile(new File(fileName), "rw");
  }

  public void deregister(long queryId) {
    Map<String, SerializationRecorder> dataId2Recorders = recorders.remove(queryId);
    if (dataId2Recorders == null) {
      return;
    }
    for (SerializationRecorder recorder : dataId2Recorders.values()) {
      try {
        recorder.closeFile();
      } catch (IOException e) {
        logger.warn(String.format("Failed to close file in method deregister(%d), because %s",
            queryId, e.toString()));
      }
    }
  }

  private void makeDirIfNecessary(String dir) {
    File file = new File(dir);
    if (file.exists() && file.isDirectory()) {
      return;
    }
    try {
      FileUtils.forceMkdir(file);
    } catch (IOException e) {
      logger.error(String.format("Failed to make dir %s, because %s", dir, e.toString()));
      throw new StorageEngineFailureException(e);
    }
  }

  private String getDirName(long queryId, String dataId) {
    return temporaryFileDir + File.separator + queryId + File.separator + dataId + File.separator;
  }

  private String getFileName(String dir, int index) {
    return dir + index;
  }

  @Override
  public void start() throws StartupException {
    // do nothing here
  }

  @Override
  public void stop() {
    try {
      for (Long queryId : recorders.keySet()) {
        deregister(queryId);
      }
      FileUtils.cleanDirectory(new File(temporaryFileDir));
    } catch (IOException ignored) {
    }
  }

  @Override
  public ServiceType getID() {
    return ServiceType.TEMPORARY_QUERY_DATA_FILE_SERVICE;
  }

  public static TemporaryQueryDataFileManager getInstance() {
    return TemporaryQueryDataFileManagerHelper.INSTANCE;
  }

  private static class TemporaryQueryDataFileManagerHelper {

    private static final TemporaryQueryDataFileManager INSTANCE = new TemporaryQueryDataFileManager();

    private TemporaryQueryDataFileManagerHelper() {
    }
  }
}
