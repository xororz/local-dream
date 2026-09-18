#include <array>
#include <cassert>
#include <cstring>
#include <iostream>
#include <vector>

#include "QnnModel.hpp"

namespace {
// Literal encodings keep the fake backend independent of runtime conversion code.
struct Samples {
  std::array<float, 4> floats;
  std::array<uint16_t, 4> halves;
  std::array<uint16_t, 4> quantized;
};
const Samples input{{-1.0f, 0.0f, 0.5f, 2.0f},
                    {0xbc00, 0x0000, 0x3800, 0x4000}, {4, 8, 10, 16}};
const Samples mean{{-2.0f, -0.5f, 0.25f, 1.5f},
                   {0xc000, 0xb800, 0x3400, 0x3e00}, {0, 6, 9, 14}};
const Samples stddev{{0.25f, 0.5f, 1.0f, 2.0f},
                     {0x3400, 0x3800, 0x3c00, 0x4000}, {9, 10, 12, 16}};
const Samples pixels{{-0.75f, -0.25f, 0.25f, 0.75f},
                     {0xba00, 0xb400, 0x3400, 0x3a00}, {5, 7, 9, 11}};
const Samples timestep{{2.0f, 2.0f, 2.0f, 2.0f},
                       {0x4000, 0x4000, 0x4000, 0x4000}, {16, 16, 16, 16}};

std::vector<uint8_t> nativeBytes(const Samples &values, Qnn_DataType_t type,
                                 size_t count) {
  const size_t width = type == QNN_DATATYPE_FLOAT_32 ? 4 : 2;
  std::vector<uint8_t> bytes(count * width);
  for (size_t i = 0; i < count; ++i) {
    const void *element = type == QNN_DATATYPE_FLOAT_32
        ? static_cast<const void *>(&values.floats[i % 4])
        : type == QNN_DATATYPE_FLOAT_16
        ? static_cast<const void *>(&values.halves[i % 4])
        : static_cast<const void *>(&values.quantized[i % 4]);
    std::memcpy(bytes.data() + i * width, element, width);
  }
  return bytes;
}

struct GraphFixture {
  bool encoder;
  Qnn_DataType_t type;
  size_t inputCount;
  size_t outputCount;
  int executions = 0;
  bool unet = false;
};

Qnn_ErrorHandle_t execute(Qnn_GraphHandle_t handle, const Qnn_Tensor_t *inputs,
                          uint32_t numInputs, Qnn_Tensor_t *outputs,
                          uint32_t numOutputs, Qnn_ProfileHandle_t,
                          Qnn_SignalHandle_t) {
  auto &fixture = *static_cast<GraphFixture *>(handle);
  assert(numInputs == (fixture.unet ? 5u : 1u));
  assert(numOutputs == (fixture.encoder ? 2u : 1u));
  for (uint32_t i = 0; i < numInputs; ++i) {
    size_t count = 1;
    for (uint32_t d = 0; d < QNN_TENSOR_GET_RANK(inputs[i]); ++d)
      count *= QNN_TENSOR_GET_DIMENSIONS(inputs[i])[d];
    const auto &values = fixture.unet && i == 1 ? timestep : input;
    const auto expected = nativeBytes(values, fixture.type, count);
    const auto buffer = QNN_TENSOR_GET_CLIENT_BUF(inputs[i]);
    assert(buffer.dataSize == expected.size());
    assert(std::memcmp(buffer.data, expected.data(), expected.size()) == 0);
  }
  for (uint32_t i = 0; i < numOutputs; ++i) {
    const auto &values = fixture.encoder ? (i == 0 ? mean : stddev) : pixels;
    const auto result = nativeBytes(values, fixture.type, fixture.outputCount);
    auto buffer = QNN_TENSOR_GET_CLIENT_BUF(outputs[i]);
    assert(buffer.dataSize == result.size());
    std::memcpy(buffer.data, result.data(), result.size());
  }
  ++fixture.executions;
  return QNN_GRAPH_NO_ERROR;
}

QnnFunctionPointers functions() {
  QnnFunctionPointers result{};
  result.qnnInterface.graphExecute = execute;
  return result;
}

Qnn_Tensor_t tensor(const char *name, Qnn_DataType_t type,
                     std::array<uint32_t, 4> &shape, bool output) {
  Qnn_Tensor_t result = QNN_TENSOR_INIT;
  result.v1.name = name;
  result.v1.type = output ? QNN_TENSOR_TYPE_APP_READ : QNN_TENSOR_TYPE_APP_WRITE;
  result.v1.dataFormat = QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
  result.v1.dataType = type;
  result.v1.rank = shape.size();
  result.v1.dimensions = shape.data();
  result.v1.memType = QNN_TENSORMEMTYPE_RAW;
  if (type == QNN_DATATYPE_UFIXED_POINT_16) {
    result.v1.quantizeParams.encodingDefinition = QNN_DEFINITION_DEFINED;
    result.v1.quantizeParams.quantizationEncoding = QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
    result.v1.quantizeParams.scaleOffsetEncoding = {0.25f, -8};
  }
  return result;
}

class ModelFixture : public QnnModel {
 public:
  explicit ModelFixture(GraphFixture &fixture)
      : QnnModel(functions(), "", "", nullptr) {
    imageShape = {1, 3, static_cast<uint32_t>(output_height),
                       static_cast<uint32_t>(output_width)};
    latentShape = {1, 4, static_cast<uint32_t>(sample_height),
                        static_cast<uint32_t>(sample_width)};
    inputTensors[0] = tensor(fixture.unet ? "sample" : "input", fixture.type,
                             fixture.encoder ? imageShape : latentShape, false);
    if (fixture.unet) {
      inputTensors[1] = tensor("timestep", fixture.type, timestepShape, false);
      inputTensors[2] = tensor("encoder_hidden_states", fixture.type, hiddenShape, false);
      inputTensors[3] = tensor("text_embeds", fixture.type, pooledShape, false);
      inputTensors[4] = tensor("time_ids", fixture.type, timeIdShape, false);
    }
    outputTensors[0] = tensor(fixture.encoder ? "mean" : "output", fixture.type,
                              fixture.encoder || fixture.unet ? latentShape : imageShape, true);
    outputTensors[1] = tensor("std", fixture.type, latentShape, true);
    graph.graph = &fixture;
    graph.inputTensors = inputTensors.data();
    graph.numInputTensors = fixture.unet ? 5 : 1;
    graph.outputTensors = outputTensors.data();
    graph.numOutputTensors = fixture.encoder ? 2 : 1;
    m_graphsInfo = &graphPointer;
    m_graphsCount = 1;
  }

  ~ModelFixture() {
    // Tensor buffers come from the real SDK allocator; graph metadata is ours.
    if (inputs || outputs)
      m_ioTensor.tearDownInputAndOutputTensors(
          inputs, outputs, graph.numInputTensors, graph.numOutputTensors);
    inputs = nullptr;
    outputs = nullptr;
    m_graphsInfo = nullptr;
    m_graphsCount = 0;
  }

 private:
  std::array<uint32_t, 4> imageShape{}, latentShape{};
  std::array<uint32_t, 4> timestepShape{1, 1, 1, 1};
  std::array<uint32_t, 4> hiddenShape{1, 1, 77, 2048};
  std::array<uint32_t, 4> pooledShape{1, 1, 1, 1280};
  std::array<uint32_t, 4> timeIdShape{1, 1, 1, 6};
  std::array<Qnn_Tensor_t, 5> inputTensors{};
  std::array<Qnn_Tensor_t, 2> outputTensors{};
  qnn_wrapper_api::GraphInfo_t graph{};
  qnn_wrapper_api::GraphInfo_t *graphPointer = &graph;
};

void checkOutput(const std::vector<float> &actual, const Samples &expected) {
  assert(actual.front() == 12345.0f && actual.back() == 12345.0f);
  for (size_t i = 1; i + 1 < actual.size(); ++i)
    assert(actual[i] == expected.floats[(i - 1) % 4]);
}

void runCase(bool sdxl, bool encoder, Qnn_DataType_t type) {
  const size_t imageCount = 3 * output_width * output_height;
  const size_t latentCount = 4 * sample_width * sample_height;
  GraphFixture graph{encoder, type, encoder ? imageCount : latentCount,
                     encoder ? latentCount : imageCount};
  ModelFixture model(graph);
  std::vector<float> source(graph.inputCount);
  for (size_t i = 0; i < source.size(); ++i) source[i] = input.floats[i % 4];
  const auto originalSource = source;
  std::vector<float> first(graph.outputCount + 2, 12345.0f);
  std::vector<float> second(graph.outputCount + 2, 12345.0f);
  // Reuse also exercises the existing lazy allocation path on the second call.
  for (int repeat = 0; repeat < 2; ++repeat) {
    StatusCode status;
    if (encoder) {
      status = sdxl
          ? model.executeVaeEncoderGraphsSDXL(source.data(), first.data() + 1,
                                              second.data() + 1)
          : model.executeVaeEncoderGraphs(source.data(), first.data() + 1,
                                          second.data() + 1);
    } else {
      status = sdxl
          ? model.executeVaeDecoderGraphsSDXL(source.data(), first.data() + 1)
          : model.executeVaeDecoderGraphs(source.data(), first.data() + 1);
    }
    if (status != StatusCode::SUCCESS)
      std::cerr << "Failed family=" << (sdxl ? "SDXL" : "SD1.5")
                << " encoder=" << encoder << " dtype=" << type << '\n';
    assert(status == StatusCode::SUCCESS);
    assert(source == originalSource);
    checkOutput(first, encoder ? mean : pixels);
    if (encoder) checkOutput(second, stddev);
  }
  assert(graph.executions == 2);
}

void runUnetCase(Qnn_DataType_t type) {
  const size_t count = 4 * sample_width * sample_height;
  GraphFixture graph{false, type, count, count, 0, true};
  ModelFixture model(graph);
  // Each input shares a repeating fixture, with enough elements for CLIP output.
  std::vector<float> source(77 * 2048);
  for (size_t i = 0; i < source.size(); ++i) source[i] = input.floats[i % 4];
  std::vector<float> output(count + 2, 12345.0f);
  assert(model.executeUnetGraphsSDXL(source.data(), 2, source.data(), source.data(),
                                    source.data(), output.data() + 1, 77, 1)
         == StatusCode::SUCCESS);
  assert(graph.executions == 1);
  checkOutput(output, pixels);
}
}  // namespace

int main() {
  assert(qnn::log::initializeLogging());
  qnn::log::setLogLevel(QNN_LOG_LEVEL_ERROR);
  // Small shapes keep the test independent of model weights or NPU hardware.
  sample_width = 2;
  sample_height = 1;
  output_width = 4;
  output_height = 2;
  for (bool sdxl : {false, true})
    for (bool encoder : {false, true})
      for (auto type : {QNN_DATATYPE_FLOAT_32, QNN_DATATYPE_FLOAT_16,
                        QNN_DATATYPE_UFIXED_POINT_16})
        runCase(sdxl, encoder, type);
  for (auto type : {QNN_DATATYPE_FLOAT_32, QNN_DATATYPE_FLOAT_16,
                    QNN_DATATYPE_UFIXED_POINT_16})
    runUnetCase(type);
  std::cout << "VAE tensor I/O: 12 cases passed (SD1.5/SDXL, encoder/decoder, "
               "FP32/FP16/quantized); 3 SDXL UNet cases passed.\n";
}
