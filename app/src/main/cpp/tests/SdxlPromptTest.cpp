#include <cassert>
#include <filesystem>
#include <iostream>
#include "TextEncoder.hpp"
#include "Tiling.hpp"

// Deterministic token IDs isolate chunk packing from the BPE vocabulary.
class TestTokenizer : public tokenizers::Tokenizer {
 public:
  std::vector<int32_t> Encode(const std::string &text) override {
    std::istringstream stream(text);
    std::vector<int32_t> ids;
    std::string word;
    while (stream >> word) ids.push_back(word.size() > 1 && word[0] == 't' && std::isdigit(word[1]) ? std::stoi(word.substr(1)) + 10 : 9);
    return ids;
  }
  std::string Decode(const std::vector<int32_t> &) override { return ""; }
  size_t GetVocabSize() override { return 49408; }
  std::string IdToToken(int32_t) override { return ""; }
  int32_t TokenToId(const std::string &) override { return 9; }
};
std::unique_ptr<tokenizers::Tokenizer> tokenizers::Tokenizer::FromBlobJSON(const std::string &) {
  return std::make_unique<TestTokenizer>();
}

std::string prompt(int count) {
  std::string text;
  for (int i = 0; i < count; ++i) text += "t" + std::to_string(i) + " ";
  return text;
}

int main(int argc, char **argv) {
  assert(argc == 2);
  const std::filesystem::path dir(argv[1]);
  std::filesystem::create_directories(dir);
  std::ofstream(dir / "tokenizer.json") << "{}";
  for (int dim : {768, 1280}) {
    const std::string suffix = dim == 768 ? "" : "_2";
    std::ofstream pos(dir / ("pos_emb" + suffix + ".bin"), std::ios::binary);
    std::vector<float> positions(77 * dim, 0.0f);
    for (int i = 0; i < 77; ++i) positions[i * dim] = static_cast<float>(i);
    pos.write(reinterpret_cast<char *>(positions.data()), positions.size() * sizeof(float));
    std::ofstream tok(dir / ("token_emb" + suffix + ".bin"), std::ios::binary);
    tok.seekp(static_cast<std::streamoff>(49408) * dim * 2 - 1);
    tok.put(0);
    const uint16_t one = fp32_to_fp16(1.0f);
    for (int id = 10; id < 310; ++id) {
      tok.seekp(static_cast<std::streamoff>(id) * dim * 2);
      tok.write(reinterpret_cast<const char *>(&one), 2);
    }
  }

  TextEncoder mnn(true, false, 0);
  mnn.loadTokenizer((dir / "tokenizer.json").string());
  mnn.loadEmbeddingTables(dir);
  for (int count : {0, 1, 74, 75, 76, 149, 150, 151, 225, 226, 300}) {
    const int chunks = std::max(1, (count + 74) / 75);
    const auto text = prompt(count);
    assert(mnn.contextLength(text) == chunks * 77);
    const auto pair = mnn.processPromptPair(text, "", chunks * 77);
    assert(pair.ids.size() == static_cast<size_t>(2 * chunks * 77));
    assert(pair.positive_embeddings.size() == static_cast<size_t>(chunks * 77 * 768));
    assert(pair.positive_embeddings_2.size() == static_cast<size_t>(chunks * 77 * 1280));
    for (int chunk = 0; chunk < chunks; ++chunk) {
      const int base = chunks * 77 + chunk * 77;
      assert(pair.ids[base] == 49406);
      const int used = std::min(75, count - chunk * 75);
      for (int i = 0; i < used; ++i) assert(pair.ids[base + i + 1] == chunk * 75 + i + 10);
      assert(pair.ids[base + used + 1] == 49407);
      // Positional embeddings reset inside each 77-token CLIP input.
      if (used) assert(pair.positive_embeddings[(chunk * 77 + 1) * 768] == 2.0f);
    }
  }
  assert(mnn.contextLength("t0 BREAK t1") == 154);
  assert(mnn.contextLength("BREAK") == 154);
  assert(mnn.contextLength("t0 BREAK") == 154);
  assert(mnn.contextLength("t0 BREAK BREAK t1") == 231);
  assert(mnn.contextLength(prompt(75) + "BREAK t75") == 154);
  auto broken = mnn.processPromptPair("t0 BREAK t1", "", 154);
  assert(broken.ids[155] == 10);
  assert(broken.ids[156] == 49407);
  assert(broken.ids[232] == 11);
  auto weighted = mnn.processPromptPair("(t0 BREAK t1:2)", "", 154);
  assert(weighted.positive_embeddings[768] == 3.2f);
  assert(weighted.positive_embeddings[78 * 768] == 3.2f);
  std::filesystem::create_directories(dir / "embeddings");
  {
    const std::string header = R"({"clip_l":{"dtype":"F32","shape":[2,768],"data_offsets":[0,6144]},"clip_g":{"dtype":"F32","shape":[2,1280],"data_offsets":[6144,16384]}})";
    const uint64_t size = header.size();
    std::ofstream fixture(dir / "embeddings/ti.safetensors", std::ios::binary);
    fixture.write(reinterpret_cast<const char *>(&size), sizeof(size));
    fixture << header;
    std::vector<float> rows(4096, 5.0f);
    fixture.write(reinterpret_cast<const char *>(rows.data()), rows.size() * sizeof(float));
  }
  mnn.loadTextualInversions((dir / "embeddings").string());
  auto inversion = mnn.processPromptPair(prompt(74) + ", ti", "", 154);
  assert(inversion.eos_positions[2] == 76);
  assert(inversion.eos_positions[3] == 3);
  assert(inversion.positive_embeddings[78 * 768] == 6.0f);
  assert(inversion.positive_embeddings_2[78 * 1280] == 6.0f);
  // A textual-inversion EOS placeholder must not pool before later text.
  assert(mnn.processWeightedPrompt("ti, t0").eos_position == 5);
  auto clip = mnn.processWeightedPrompt("t0");
  assert(clip.ids_2[1] == 10 && clip.ids_2[2] == 49407 && clip.ids_2[3] == 0);
  assert(mnn.contextLength("t0", prompt(151)) == 231);

  TextEncoder legacy_sdxl(true), patched(true, false, 3), masked(true, false, 3, true);
  for (auto *encoder : {&legacy_sdxl, &patched, &masked})
    encoder->loadTokenizer((dir / "tokenizer.json").string());
  assert(legacy_sdxl.contextLength(prompt(100)) == 77);
  assert(patched.contextLength(prompt(100)) == 154);
  assert(patched.contextLength(prompt(300)) == 231);
  assert(masked.contextLength("t0") == 231);
  assert(patched.tokenizeInfo(prompt(226), 227).overflow_offset >= 0);

  TextEncoder sd15(false);
  sd15.loadTokenizer((dir / "tokenizer.json").string());
  auto legacy = sd15.processPromptPair(prompt(80), "t1");
  assert(legacy.ids.size() == 154);
  assert(legacy.ids[153] == 49407);
  assert(legacy.ids[152] == 84);
  assert(sd15.contextLength(prompt(300)) == 77);
  assert(sd15.tokenizeInfo("t0 BREAK t1").count == 5);

  TextEncoder sd15_mnn(false, false, 0);
  sd15_mnn.loadTokenizer((dir / "tokenizer.json").string());
  sd15_mnn.loadEmbeddingTables(dir);
  sd15.loadEmbeddingTables(dir);
  for (int count : {0, 1, 74, 75, 76, 149, 150, 151, 225, 226, 300}) {
    const int chunks = std::max(1, (count + 74) / 75);
    const auto text = prompt(count);
    const int length = sd15_mnn.contextLength(text, "t1");
    assert(length == chunks * 77);
    auto pair = sd15_mnn.processPromptPair(text, "t1", length);
    assert(pair.ids.size() == static_cast<size_t>(2 * length));
    assert(pair.positive_embeddings.size() == static_cast<size_t>(length * 768));
    assert(pair.negative_embeddings.size() == pair.positive_embeddings.size());
    assert(pair.positive_embeddings_2.empty() && pair.negative_embeddings_2.empty());
    for (int chunk = 0; chunk < chunks; ++chunk) {
      const int base = length + chunk * 77;
      assert(pair.ids[base] == 49406);
      const int used = std::max(0, std::min(75, count - chunk * 75));
      for (int i = 0; i < used; ++i)
        assert(pair.ids[base + i + 1] == chunk * 75 + i + 10);
      assert(pair.ids[base + used + 1] == 49407);
      if (used) assert(pair.positive_embeddings[(chunk * 77 + 1) * 768] == 2.0f);
      if (chunk) {
        // Shorter negative prompts receive complete empty CLIP chunks.
        assert(pair.ids[chunk * 77] == 49406);
        assert(pair.ids[chunk * 77 + 1] == 49407);
      }
    }
    assert(sd15_mnn.tokenizeInfo(text, chunks * 75 + 2).overflow_offset == -1);
  }
  assert(sd15_mnn.contextLength("t0", prompt(151)) == 231);
  assert(sd15_mnn.contextLength("t0 BREAK BREAK t1") == 231);
  assert(sd15_mnn.contextLength(prompt(75) + "BREAK t75") == 154);
  assert(sd15_mnn.contextLength("t0 BREAK") == 154);
  auto sd15_break = sd15_mnn.processPromptPair("(t0 BREAK t1:2)", "", 154);
  auto sd15_first = sd15.processWeightedPrompt("(t0:2)");
  auto sd15_second = sd15.processWeightedPrompt("(t1:2)");
  assert(std::equal(sd15_first.weighted_embeddings.begin(), sd15_first.weighted_embeddings.end(),
                    sd15_break.positive_embeddings.begin()));
  assert(std::equal(sd15_second.weighted_embeddings.begin(), sd15_second.weighted_embeddings.end(),
                    sd15_break.positive_embeddings.begin() + 77 * 768));
  // Enabling chunking preserves existing short-prompt weighting and padding.
  for (const auto &text : {"", "t0 t1", "(t0:1.5) [t1]", "BREAKFAST t0"}) {
    assert(sd15_mnn.processPromptPair(text, "").positive_embeddings ==
           sd15.processPromptPair(text, "").positive_embeddings);
  }
  sd15_mnn.loadTextualInversions((dir / "embeddings").string());
  auto sd15_inversion = sd15_mnn.processPromptPair(prompt(74) + ", ti", "", 154);
  assert(sd15_inversion.eos_positions[2] == 76);
  assert(sd15_inversion.eos_positions[3] == 3);
  assert(sd15_inversion.positive_embeddings[78 * 768] == 6.0f);
  // Loading SD1.5 textual inversions must not disable BREAK handling.
  assert(sd15_mnn.contextLength("t0 BREAK t1") == 154);

  auto [pixels, latents, ox, oy, lx, ly] = calculate_vae_tile_positions(1024, 1024, 640);
  assert(pixels.size() == 4 && latents.size() == 4);
  assert(ox == 256 && oy == 256 && lx == 32 && ly == 32);
  assert(pixels.back() == std::make_pair(384, 384));
  assert(std::get<0>(calculate_vae_tile_positions(1024, 1024, 1024)).size() == 1);
  std::filesystem::remove_all(dir);
  std::cout << "SDXL and SD1.5 MNN chunk boundaries, BREAK, weighting, padding, context selection, legacy SD1.5 QNN and VAE tile geometry passed\n";
}
