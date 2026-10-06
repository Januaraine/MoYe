# Moye Reader / 墨页阅读器

Moye is a paginated local novel reader for Android. It opens TXT and EPUB files you already have, keeps them on the device, and does not download or sell books.

墨页是一款分页式的 Android 本地小说阅读器。它打开你已经拥有的 TXT 和 EPUB，文件只留在这台设备上。

## Reading model / 阅读模型

Moye uses paginated reading.

- A page can contain multiple sentences.
- Page is the navigation unit.
- Sentence is the reveal unit.
- Sentences are revealed sequentially within a page.
- Each newly revealed sentence supports character-by-character Typewriter Effect.
- Typewriter speed is continuously adjustable with a slider.
- Typewriter Speed and Auto Play Speed are independent settings.
- Typewriter Effect can be disabled.
- Disabling the effect does NOT disable sequential sentence reveal.
- Page transition animation is disabled.
- EPUB embedded covers should be extracted and displayed.
- Books without covers should receive generated text-based covers.

Multiple files can be selected and imported at once. Duplicate books already in the bookshelf are skipped. Duplicate detection uses a content hash, not the title.

进入新的一页时只显示第一句。页眉和页脚默认隐藏。点按屏幕中间打开或关掉菜单，并在打开时暂停自动播放；这个点按不会翻页，也不会出现下一句。点按左侧是上一页，点按右侧会补全正在打字的句子、出现下一句，或在本页都出现后进入下一页。滑动和菜单里的上一页 / 下一页直接切换页面，没有翻页动画。

逐字速度和自动播放速度是两条分开的拖动条。自动播放把全文看成一句接一句的序列，同一时间只有一个播放循环。当前句完全显示之后才出现下一句；换段使用同一套间隔，不会提前蹦出下一段，也不会因为换段再多等一次。关闭逐字显示之后，点按右侧仍然逐句出现，只是每一句会马上完整显示。手动点按会取消当前循环，再从当前句子重新计时。

EPUB 里合法的内嵌封面会用在书架上。没有封面的书用书名生成文字封面，不会给已经有封面的 EPUB 再做一张文字封面。

产品范围见 [小说阅读器_MVP计划.md](小说阅读器_MVP计划.md)。构建和测试见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。许可证见 [REARMED.md](REARMED.md)。
