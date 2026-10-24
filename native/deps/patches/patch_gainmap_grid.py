"""Keep AV1 gain-map packets small without changing their samples or dimensions."""
from pathlib import Path
import sys

p = Path(sys.argv[1]) / 'libheif/api/libheif/heif.cc'
s = p.read_text()
marker = '// UltraSlim: lossless gain-map grid for bounded decoder input packets.'
if marker in s:
    sys.exit(0)
old = '''  auto gainmap_encoding_result = ctx->context->encode_image(gain_map_image->image, encoder, options,
                                                            heif_image_input_class_gain_map);
  if (gainmap_encoding_result.error) {
    return gainmap_encoding_result.error.error_struct(ctx->context.get());
  }

  std::shared_ptr<ImageItem> gain_map_image_item = *gainmap_encoding_result;'''
new = '''  // UltraSlim: lossless gain-map grid for bounded decoder input packets.
  // Tile input_class is normal, intentionally bypassing the single-map retry cache.
  // Padding is outside the grid canvas; every original sample remains unchanged.
  std::shared_ptr<ImageItem> gain_map_image_item;
  const auto& pixels = gain_map_image->image;
  constexpr uint32_t tile_size = 512;
  if (encoder->plugin->compression_format == heif_compression_AV1 &&
      (pixels->get_width() > tile_size || pixels->get_height() > tile_size)) {
    auto grid = ImageItem_Grid::add_new_grid_item(ctx->context.get(),
        pixels->get_width(), pixels->get_height(),
        (pixels->get_height() + tile_size - 1) / tile_size,
        (pixels->get_width() + tile_size - 1) / tile_size, &options);
    if (grid.error) return grid.error.error_struct(ctx->context.get());
    if (auto profile = pixels->get_color_profile_nclx()) {
      auto colr = std::make_shared<Box_colr>();
      colr->set_color_profile(profile);
      grid.value->add_property(colr, false);
    }
    for (uint32_t y = 0; y < pixels->get_height(); y += tile_size) {
      for (uint32_t x = 0; x < pixels->get_width(); x += tile_size) {
        auto tile = pixels->extract_image_area(x, y, tile_size, tile_size,
                                               ctx->context->get_security_limits());
        if (tile.error) return tile.error.error_struct(ctx->context.get());
        tile.value->set_color_profile_nclx(pixels->get_color_profile_nclx());
        auto tile_error = grid.value->add_image_tile(x / tile_size, y / tile_size,
                                                    tile.value, encoder);
        if (tile_error) return tile_error.error_struct(ctx->context.get());
      }
    }
    gain_map_image_item = grid.value;
  } else {
    auto encoded = ctx->context->encode_image(pixels, encoder, options,
                                              heif_image_input_class_gain_map);
    if (encoded.error) return encoded.error.error_struct(ctx->context.get());
    gain_map_image_item = encoded.value;
  }'''
if old not in s:
    raise RuntimeError('upstream gain-map encoder changed')
p.write_text(s.replace(old, new))
