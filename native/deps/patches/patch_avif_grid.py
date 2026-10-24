"""Store large HEIC/AVIF primary images as baseline-sized grid cells, at full resolution."""
from pathlib import Path
import sys

grid_path = Path(sys.argv[1]) / 'libheif/image-items/grid.cc'
grid_source = grid_path.read_text()
grid_source = grid_source.replace(
    '  file->add_ispe_property(grid_id, output_width, output_height, false);',
    '''  // Keep the in-memory grid geometry visible to subsequent tmap encoding.
  auto ispe = std::make_shared<Box_ispe>();
  ispe->set_size(output_width, output_height);
  grid_image->add_property(ispe, false);''')
grid_path.write_text(grid_source)

p = Path(sys.argv[1]) / 'libheif/api/libheif/heif.cc'
s = p.read_text()
marker = '// UltraSlim: bound AVIF primary dimensions for platform decoders.'
if marker in s:
    # Upgrade installed dependencies as well as fresh builds.
    s = s.replace('encoder->plugin->compression_format != heif_compression_AV1 ||',
                  '(encoder->plugin->compression_format != heif_compression_AV1 &&\n         encoder->plugin->compression_format != heif_compression_HEVC) ||')
    p.write_text(s)
    sys.exit(0)
old = '''  auto encodingResult = ctx->context->encode_image(input_image->image,
                                     encoder,
                                     options,
                                     heif_image_input_class_normal);'''
new = '''  // UltraSlim: bound AVIF primary dimensions for platform decoders.
  auto encodingResult = [&]() -> Result<std::shared_ptr<ImageItem>> {
    const auto& pixels = input_image->image;
    if ((encoder->plugin->compression_format != heif_compression_AV1 &&
         encoder->plugin->compression_format != heif_compression_HEVC) ||
        (pixels->get_width() <= 4096 && pixels->get_height() <= 4096) ||
        pixels->has_alpha() || options.image_orientation != heif_orientation_normal) {
      return ctx->context->encode_image(pixels, encoder, options, heif_image_input_class_normal);
    }
    constexpr uint32_t tile_size = 2048;
    // A subsampled grid canvas must be even. A clean aperture keeps odd input
    // dimensions unchanged without rescaling or discarding edge pixels.
    const bool subsampled = pixels->get_chroma_format() == heif_chroma_420 || pixels->get_chroma_format() == heif_chroma_422;
    const uint32_t canvas_width = subsampled ? (pixels->get_width() + 1) & ~1u : pixels->get_width();
    const uint32_t canvas_height = pixels->get_chroma_format() == heif_chroma_420
        ? (pixels->get_height() + 1) & ~1u : pixels->get_height();
    auto grid = ImageItem_Grid::add_new_grid_item(ctx->context.get(),
        canvas_width, canvas_height,
        (pixels->get_height() + tile_size - 1) / tile_size,
        (pixels->get_width() + tile_size - 1) / tile_size, &options);
    if (grid.error) return grid.error;
    if (canvas_width != pixels->get_width() || canvas_height != pixels->get_height()) {
      auto clap = std::make_shared<Box_clap>();
      clap->set(pixels->get_width(), pixels->get_height(), canvas_width, canvas_height);
      grid.value->add_property(clap, true);
    }
    // Profiles must be on the derived canvas as well as its cells. ICC last,
    // matching the AV1 coded-item workaround for Android Display P3.
    if (auto nclx = pixels->get_color_profile_nclx()) {
      auto colr = std::make_shared<Box_colr>();
      colr->set_color_profile(nclx);
      grid.value->add_property(colr, false);
    }
    if (auto icc = pixels->get_color_profile_icc()) {
      auto colr = std::make_shared<Box_colr>();
      colr->set_color_profile(icc);
      grid.value->add_property(colr, false);
    }
    for (uint32_t y = 0; y < pixels->get_height(); y += tile_size) {
      for (uint32_t x = 0; x < pixels->get_width(); x += tile_size) {
        const uint32_t width = std::min(tile_size, pixels->get_width() - x);
        const uint32_t height = std::min(tile_size, pixels->get_height() - y);
        auto tile = pixels->extract_image_area(x, y, width, height,
                                               ctx->context->get_security_limits());
        if (tile.error) return tile.error;
        // Extend edges, avoiding black padding influencing lossy edge blocks.
        auto padding = tile.value->extend_padding_to_size(tile_size, tile_size, true,
                                                          ctx->context->get_security_limits());
        if (padding) return padding;
        tile.value->forward_all_metadata_from(pixels);
        auto error = grid.value->add_image_tile(x / tile_size, y / tile_size, tile.value, encoder);
        if (error) return error;
      }
    }
    return std::static_pointer_cast<ImageItem>(grid.value);
  }();'''
if old not in s:
    raise RuntimeError('upstream primary image encoder changed')
p.write_text(s.replace(old, new))
