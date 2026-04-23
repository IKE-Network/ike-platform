// svgo.config.mjs — Inline CSS for prawn-svg compatibility
//
// prawn-svg (used by asciidoctorj-pdf) cannot parse <style> blocks with
// CSS class selectors. This config inlines those rules as element-level
// style attributes, which prawn-svg handles correctly.
//
// Deliberately minimal — only the two transformations needed for PDF:
//   1. inlineStyles: <style>.cls { fill: #fff }</style> → style="fill: #fff"
//   2. removeStyleElement: removes the now-empty <style> block
//
// No other SVG optimizations are applied, preserving Kroki/mmdc output
// structure for debuggability.

export default {
  // Disable preset-default entirely — we only want CSS inlining
  plugins: [
    {
      name: 'inlineStyles',
      params: {
        onlyMatchedOnce: false,       // Inline even if selector matches multiple elements
        removeMatchedSelectors: true,  // Remove inlined rules from <style> block
        useMqs: ['', 'screen'],        // Process default and screen media queries
      },
    },
    // Remove the <style> element after all rules have been inlined
    'removeStyleElement',
  ],
};
