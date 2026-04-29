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