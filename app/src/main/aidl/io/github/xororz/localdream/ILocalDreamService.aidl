/*
 * Copyright 2026 Layla Network Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.xororz.localdream;

import io.github.xororz.localdream.data.ModelInfo;

interface ILocalDreamService {
    // Lifecycle
    boolean startModel(String modelId, int width, int height);
    void stopModel();

    // State: 0=Idle, 1=Starting, 2=Running, -1=Error
    int getState();
    String getErrorMessage();

    // The port the HTTP server is listening on
    int getPort();

    // get list of available models in Local-Dream
    List<ModelInfo> getModels();
}