"""生成本项目设计线框图与静态源码核查附录；不导入或运行参考业务。"""
from pathlib import Path
import ast
import hashlib
import html
import json
import re
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parents[2]
SOURCE = WORKSPACE / '参考项目' / 'ai-picture-editor-main'
OUT = ROOT / '图稿'
FONT = Path('C:/Windows/Fonts/msyh.ttc')
W, H = 1600, 1080
INK, MUTED, LINE, BLUE = '#24354b', '#68788c', '#d6dfeb', '#2664c4'

def font(size=20):
    return ImageFont.truetype(str(FONT), size)

def page(title, subtitle):
    img = Image.new('RGB', (W, H), '#f1f4f9')
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, W, 48), fill='#1d304c')
    d.text((22, 11), '若依共享平台   系统菜单由服务端授权   |   AI 图片编辑', font=font(21), fill='white')
    d.text((1310, 12), '统一账号  ·  布局设置', font=font(18), fill='white')
    d.rectangle((0, 48, 170, H-65), fill='white')
    d.text((20, 72), '授权主菜单', font=font(20), fill=INK)
    for i, name in enumerate(['AI 图片编辑', '创作', '图片编辑', '营销与导出', '批量处理', '能力与评测']):
        y = 120 + i*47
        if i == 0:
            d.rounded_rectangle((10,y-7,158,y+32),radius=6,fill='#e7f0ff')
        d.text((20,y), name,font=font(18),fill=BLUE if i==0 else INK)
    d.text((16, 462), '其他授权业务',font=font(17),fill=MUTED)
    d.rectangle((170,48,W,94),fill='white')
    d.text((190,60),'TagsView：创作  ×     图片编辑  ×     批量处理  ×',font=font(18),fill=INK)
    d.text((195,113), title,font=font(29),fill=INK)
    d.text((195,158),subtitle,font=font(18),fill=MUTED)
    d.rectangle((0,H-65,W,H),fill='#e6edf7')
    d.text((20,H-52),'设计线框图 2026.10-r1 · 示意商品与模拟状态 · 不作为真实业务验收截图',font=font(19),fill=INK)
    d.text((20,H-27),'导航示意为 navType=1；实现同时沿用若依 navType=2 混合 / navType=3 顶部与用户 TagsView 偏好。',font=font(17),fill=MUTED)
    return img, d

def box(d, x,y,w,h,title='', fill='white'):
    d.rounded_rectangle((x,y,x+w,y+h),radius=10,fill=fill,outline=LINE,width=1)
    if title: d.text((x+16,y+13),title,font=font(21),fill=INK)

def text(d,x,y,value,size=18,color=INK):
    d.multiline_text((x,y),value,font=font(size),fill=color,spacing=8)

def button(d,x,y,w,label,primary=False):
    d.rounded_rectangle((x,y,x+w,y+38),radius=6,fill=BLUE if primary else '#f1f5fc',outline=BLUE if primary else LINE)
    text(d,x+10,y+7,label,17,'white' if primary else INK)

def field(d,x,y,w,label,value):
    text(d,x,y,label,17,MUTED)
    d.rounded_rectangle((x,y+27,x+w,y+65),radius=5,fill='#fafcff',outline=LINE)
    text(d,x+10,y+34,value,17)

def product(d,x,y,w,h,variant=0):
    palettes=['#e8ecde','#dfe8ed','#ede3de','#dce7dd']
    d.rounded_rectangle((x,y,x+w,y+h),radius=8,fill=palettes[variant%4])
    cx,cy=x+w//2,y+h//2
    rw,rh=int(w*.24),int(h*.32)
    d.ellipse((cx-rw,cy+rh-8,cx+rw+24,cy+rh+12),fill='#bcc6ca')
    d.ellipse((cx+rw-12,cy-rh+12,cx+rw+37,cy+rh-14),outline='#557987',width=max(3,w//40))
    d.rounded_rectangle((cx-rw,cy-rh,cx+rw,cy+rh),radius=15,fill='#698d9a',outline='#557987',width=2)
    d.ellipse((cx-rw,cy-rh-5,cx+rw,cy-rh+10),fill='#456b79')

def create():
    img,d=page('创作与候选选择','保留 React 创作页与候选页；输入保留，真实模型与测试模式清晰显示。')
    box(d,195,199,480,777,'描述你需要的图片')
    text(d,213,249,'提示词（必填，1–1500 字）',18)
    box(d,213,279,442,115,fill='#fafcff')
    text(d,227,294,'白色陶瓷杯置于浅木桌面，\n晨光从左侧照入，保留商品细节。',18)
    field(d,213,414,442,'画幅','1:1  /  4:5  /  3:4  /  9:16  /  16:9')
    field(d,213,499,213,'候选数量（1–6）','4')
    field(d,442,499,213,'随机种子（可选）','12345')
    field(d,213,583,442,'反向提示词（可选）','文字、水印、多余物体')
    text(d,213,672,'参考图：最多 3 张，验证本人归属',17,MUTED)
    for i in range(3):
        product(d,213+i*108,708,93,75,i)
    button(d,213,804,180,'上传 / 从素材库选')
    button(d,213,863,180,'生成候选图片',True)
    text(d,213,925,'缺模型：保留输入并提示配置；不自动换 Mock。',16,MUTED)
    box(d,696,199,880,777,'候选图片 · 示例显示 4 张，支持 1–6 张')
    text(d,714,247,'生成完成 · Provider / 模型 / 实际尺寸可查看',17,MUTED)
    for i in range(4):
        x,y=714+(i%2)*427,290+(i//2)*296
        product(d,x,y,407,213,i)
        text(d,x+8,y+224,f'候选 {i+1} · 1080 × 1080',18)
        button(d,x+206,y+219,90,'放大')
        button(d,x+306,y+219,99,'采用编辑',i==1)
    text(d,714,898,'采用第二张后进入编辑；其余候选保留在图片墙。',18)
    button(d,714,930,140,'重新生成')
    button(d,865,930,140,'下载候选')
    img.save(OUT/'创作与候选.png')

def editor():
    img,d=page('完整画布、会话与图层','React / Konva 保留专业交互；正式文档与任务状态由若依 Java 保存。')
    box(d,195,199,285,777,'会话与工具计划')
    button(d,211,245,249,'新建 / 打开历史会话')
    text(d,211,302,'马克杯商品精修\n运动鞋场景图\n透明主体素材',18)
    d.line((211,414,464,414),fill=LINE,width=2)
    text(d,211,431,'当前指令',18,MUTED)
    box(d,211,465,253,103,fill='#f4f7fc')
    text(d,223,477,'拆出主体，提高主体亮度，\n再向右移动 40 像素。',17)
    text(d,211,591,'计划待确认',20,BLUE)
    text(d,211,631,'1  拆分背景与主体\n2  调整主体亮度 +0.2\n3  移动主体 +40 px',17)
    button(d,211,744,118,'确认执行',True)
    button(d,341,744,123,'取消后续')
    field(d,211,809,253,'输入修图要求','保留输入，可继续追问…')
    button(d,211,907,118,'发送指令',True)
    box(d,498,199,794,777)
    text(d,514,213,'马克杯商品精修 · 1080×1080 · 已保存版本 12',19)
    d.line((513,248,1276,248),fill=LINE,width=1)
    labels=['裁剪','翻转','去背景','换背景','扩图','增强','调色','拆层']
    for i,label in enumerate(labels): button(d,514+i*95,262,86,label)
    for i,label in enumerate(['点选','笔刷','消除','替换','成层','撤销','重做','对比']):
        button(d,514+i*95,312,86,label,label=='点选')
    d.rectangle((514,366,1276,793),fill='#e5eaf1')
    product(d,707,386,388,388,0)
    d.ellipse((818,462,1000,701),outline=BLUE,width=4)
    d.ellipse((900,568,914,582),fill=BLUE)
    text(d,532,375,'蓝线示意选区；拖图 / 缩放 / 对比',16,MUTED)
    text(d,532,755,'60%   [－] [＋]   适应窗口   实际像素',17)
    text(d,514,815,'图片墙 · 未采用结果不覆盖图层',18)
    for i in range(5): product(d,514+i*151,850,137,98,i)
    box(d,1310,199,266,777,'图层 / 参数 / 历史')
    text(d,1326,247,'[显示] 主体 · 图像（选中）\n[显示] 背景 · 图像\n[显示] 文字 · 今日新品',16)
    field(d,1326,365,234,'位置 x / y','40 / 0')
    field(d,1326,451,234,'倍率 / 角度 / 透明度','1.0 / 0° / 100%')
    button(d,1326,533,234,'置顶 / 置底 / 上移 / 下移')
    text(d,1326,592,'调色共 11 项',19)
    text(d,1326,634,'亮度 · 对比度 · 高光\n阴影 · 色温 · 色调\n饱和度 · 自然饱和度\n锐度 · 清晰度 · 暗角',17)
    text(d,1326,766,'即时预览；提交才保存',17,MUTED)
    button(d,1326,806,234,'应用调色',True)
    text(d,1326,866,'文字拆层：用户明确勾选\n当前选区随画布版本失效\n导出使用服务端合成',16,MUTED)
    img.save(OUT/'编辑工作台.png')

def states():
    img,d=page('计划确认、运行与异常恢复','状态设计：等待确认、失败单步重试、版本冲突、真实模型不可用。')
    panels=[(195,199,'待确认：先核对范围'),(899,199,'运行 / 单步失败'),(195,605,'旧画布 / 选区拒绝'),(899,605,'模型缺失与可用能力')]
    for x,y,title in panels: box(d,x,y,677,370,title)
    text(d,217,252,'目标：调亮主体、修补选区，再导出竖版\n作用对象：主体层；固定遮罩 M-7\n输出：前两步修改图层，投放结果进入图片墙',19)
    text(d,217,365,'1 调色 → 2 区域修补 → 3 投放尺寸\n本次会向已批准的模型服务提交图片。',19)
    button(d,217,462,160,'确认执行',True)
    button(d,393,462,160,'取消后续')
    text(d,217,520,'未确认前不进行多步图片写入。',17,MUTED)
    text(d,921,252,'1 调色：成功（结果保留）\n2 区域修补：失败，模型请求超时\n3 投放尺寸：等待第 2 步',19)
    d.rounded_rectangle((921,364,1548,423),radius=6,fill='#fff0e6')
    text(d,937,379,'外部结果待核对：不会自动再次付费调用。',18,'#905125')
    button(d,921,462,171,'核对 / 重试第 2 步',True)
    button(d,1108,462,150,'取消后续')
    text(d,921,520,'运行中的请求按供应商实际取消能力显示。',17,MUTED)
    text(d,217,659,'另一标签页已更新当前画布。\n计划基于版本 12，当前为 14。\n旧遮罩不再适用，未采用迟到结果。',19)
    button(d,217,799,160,'读取最新画布',True)
    button(d,393,799,160,'重新选择 / 规划')
    text(d,217,871,'输入与原计划保留；不静默覆盖新内容。\n重新确认绑定计划版本和 hash。',17,MUTED)
    text(d,921,659,'SAM 模型未就绪：点选暂不可用。\nOCR 模型缺失：文字拆层暂不可用。\n生成模型未配置：生图 / 修补暂不可用。',19)
    button(d,921,799,155,'改用笔刷',True)
    button(d,1092,799,150,'查看能力状态')
    text(d,921,871,'缩放、画布变换、调色、尺寸适配仍可使用。\n测试圆形选区 / Mock 不标记为真实 AI。',17,MUTED)
    img.save(OUT/'计划与异常.png')

def batch():
    img,d=page('营销、批量、导出与评测','完整业务页面设计；评测仅管理员可见，测试指标与业务接受分开。')
    box(d,195,199,677,390,'营销与投放尺寸')
    for i,label in enumerate(['商品主图','场景图','模特展示','促销海报']): button(d,213+i*160,247,148,label,i==3)
    field(d,213,310,632,'文案 ≤200 / 数量 1–4 / 比例','新品首发 限时 8 折   |   4 张   |   1:1')
    text(d,213,393,'投放比例：[选]1:1  [选]4:5  3:4  [选]9:16  16:9',17)
    text(d,213,429,'居中留白，主体不裁切；结果只进入图片墙。',18,MUTED)
    button(d,213,476,178,'生成营销图',True)
    button(d,409,476,178,'适配投放尺寸')
    button(d,605,476,178,'选择物料导出 ZIP')
    text(d,213,534,'海报文字与商品保真须人工核对；清单可回读。',17,MUTED)
    text(d,213,562,'画布导出背景：白底（默认） / 勾选保留透明',16,MUTED)
    box(d,899,199,677,390,'批量流水线（最多 20 张）')
    text(d,919,251,'选择图片：上传 / 历史素材 · 已选 3 张',18)
    for i in range(3): product(d,919+i*121,290,107,78,i)
    text(d,919,389,'去背景 → 换背景 → 调色 → 增强 → 扩图 → 投放尺寸',16)
    text(d,919,433,'格式：[选] PNG（保留透明）  [选] JPG（白底）',18)
    button(d,919,483,160,'开始处理',True)
    button(d,1095,483,160,'查看历史任务')
    text(d,919,548,'处理顺序与分支明确；超过上限拒绝并保留输入。',17,MUTED)
    box(d,195,610,677,366,'批量结果：部分成功')
    text(d,213,663,'成功 2 张 · 失败 1 张 · 原失败证据保留',20,'#905125')
    text(d,213,712,'商品1.png   成功   6 个输出\n商品2.png   失败   模型调用超时\n商品3.png   成功   6 个输出',19)
    button(d,213,837,176,'仅重试商品 2',True)
    button(d,405,837,190,'打包成功项 ZIP')
    text(d,213,906,'仅失败单项重试；成功项不再次调用模型。',17,MUTED)
    box(d,899,610,677,366,'能力与评测（管理员）')
    text(d,919,663,'数据集：商品样本 v1 · 文件 hash 已登记\n模式：真实 rembg / SAM / OCR · 模型版本可追溯',18)
    text(d,919,745,'Alpha IoU  0.86     MAE  8.4     PSNR  26.1\n尺寸符合 / 主体容纳     区域外差分：0\nOCR / 超分 / 生成质量：另有人工复核',18)
    button(d,919,855,155,'执行评测',True)
    button(d,1090,855,160,'下载逐项报告')
    text(d,919,922,'示意数值；真实执行结果与用户接受分开记录。',17,MUTED)
    img.save(OUT/'营销批量与评测.png')

def controls():
    img,d=page('工具参数、导出与等待状态','专用业务控件沿用 React；通用选择、提示、下载与权限经若依宿主接入。')
    box(d,195,199,412,777,'完整调色面板')
    field(d,211,246,379,'目标图层','主体（选中）')
    labels=['亮度','对比度','高光','阴影','色温','色调','饱和度','自然饱和度','锐度','清晰度','暗角']
    for i,label in enumerate(labels):
        y=334+i*43
        text(d,211,y,label,17)
        d.line((318,y+15,518,y+15),fill='#b4c5dd',width=4)
        knob=318 if label=='暗角' else 415
        d.ellipse((knob-9,y+6,knob+9,y+24),fill=BLUE)
        text(d,534,y,'0.00',17,MUTED)
    text(d,211,826,'前十项 -1～1；暗角 0～1',17,MUTED)
    button(d,211,869,177,'应用调色',True)
    button(d,405,869,177,'取消预览')
    text(d,211,929,'预览近似；原像素与透明度在服务端核对。',15,MUTED)
    box(d,626,199,448,777,'区域、文字与导出设置')
    field(d,642,245,416,'区域替换：描述（必填）','把所选杯子换成白色陶瓷杯')
    text(d,642,326,'目标：主体层 × 当前选区\n无选区时改整层，计划须明确范围。',17,MUTED)
    button(d,642,385,180,'替换区域',True)
    field(d,642,447,416,'拆层设置','[未选] 识别并拆出 OCR 文字')
    field(d,642,531,416,'文字内容 / 字号 / 颜色','今日新品   /   32   /   #2664c4')
    button(d,642,607,180,'保存文字样式',True)
    text(d,642,667,'导出内容：当前画布 / 指定物料\n导出背景：白底（默认） / 保留透明\n格式：PNG 或物料 ZIP\nZIP 附文件名、尺寸、比例与来源清单',17)
    button(d,642,818,180,'确认导出',True)
    button(d,842,818,210,'重新获取下载地址')
    text(d,642,901,'地址失效重新鉴权；不公开存储桶。',16,MUTED)
    box(d,1094,199,482,240,'空状态')
    text(d,1112,250,'还没有可编辑图片。\n上传图片或从创作页生成候选。',19)
    button(d,1112,340,180,'上传 / 开始创作',True)
    box(d,1094,458,482,240,'加载与任务进度')
    text(d,1112,510,'SAM 模型预热中…\n正在计算当前图片 embedding。',19)
    d.rounded_rectangle((1112,588,1558,604),radius=8,fill='#dce8fa')
    d.rounded_rectangle((1112,588,1375,604),radius=8,fill=BLUE)
    text(d,1112,622,'阶段进度 60%（设计示意）',17,MUTED)
    box(d,1094,718,482,258,'取消与恢复')
    text(d,1112,770,'请求未完成，保留输入。\n可取消尚未开始的后续任务；\n运行中的模型调用按真实能力处理。',18)
    button(d,1112,879,180,'取消后续')
    button(d,1308,879,180,'读取最新状态',True)
    img.save(OUT/'工具参数与导出.png')

def gallery():
    names=['创作与候选','编辑工作台','计划与异常','营销批量与评测','工具参数与导出']
    body=''.join(f'<section id="p{i}"><h2>{html.escape(name)}</h2><img src="{name}.png" alt="{name}设计线框图"><p>设计图，非产品运行截图。放大图片查看控件、字段与状态。</p></section>' for i,name in enumerate(names))
    links=''.join(f'<a href="#p{i}">{name}</a>' for i,name in enumerate(names))
    content=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AI 图片编辑界面设计</title><style>body{{font-family:"Microsoft YaHei",sans-serif;background:#edf2f8;color:#24354b;margin:0}}header{{position:sticky;top:0;background:#fff;padding:18px 24px;border-bottom:1px solid #ccd8e8;z-index:1}}h1{{font-size:24px;margin:0 0 12px}}nav{{display:flex;gap:12px;flex-wrap:wrap}}a{{color:#2664c4}}main{{padding:20px}}section{{scroll-margin-top:130px;background:white;padding:20px;border-radius:12px;margin-bottom:24px}}img{{width:100%;max-width:1600px;display:block}}p{{line-height:1.7}}.notice{{padding:12px;background:#fff1dc}}@media(max-width:800px){{section{{padding:8px}}header{{position:static}}}}</style><header><h1>AI 图片编辑完整界面设计 · 2026.10-r1</h1><nav>{links}<a href="../完整迁移设计.md">主设计正文</a></nav></header><main><p class="notice">本页仅展示拟实现界面，商品图和状态均为设计示意。正式运行前端沿用 React/TypeScript/Konva，若依负责授权主菜单、账号与业务状态。</p>{body}</main></html>'''
    (OUT/'界面设计.html').write_text(content,encoding='utf-8')

def source_link(path):
    return '../../../参考项目/ai-picture-editor-main/'+path.as_posix()

def audit():
    files=sorted(p for p in SOURCE.rglob('*') if p.is_file() and '.git' not in p.parts and 'node_modules' not in p.parts)
    lines=['# AI 图片编辑源码核查附录','', '> 类型：静态源码与设计图稿核查附录；日期：2026-10-02；关联：[完整迁移设计](完整迁移设计.md)。', '', '本附录固定本轮读取对象、接口和测试出处，不维护第二套设计或长期实施状态。扫描未导入应用，也未运行测试、安装依赖、启动源服务或访问数据库。源码指纹对应本轮工作树，不能证明外部原始发布、版权许可或实际运行版本。', '', '## 1. 静态核查范围与重要边界','',f'参考树共扫描 {len(files)} 个文件。主要处理源码、路由、注册表、前端入口、页面/hooks/算法、测试、配置、部署和锁文件；下表保存全部文件相对路径与 SHA256。', '', '- 工具注册表有22个 `ToolSpec`，与README21个宣传口径不一致；以实际注册清单为迁移范围。','- `PlaceholderPage.tsx` 存在但未接入 `App.tsx`；`shape` 是模型枚举，没有完整绘图入口，异常类/空继承中的 `pass` 不当作功能占位。','- SAM通过rembg的encoder/decoder实现，embedding按内容hash LRU4；auto异常时用圆形遮罩。OCR auto异常返回空；这些不构成真实能力证据。','- Mock生图/编辑是本地合成占位图，增强为LANCZOS。DashScope有真实接口实现但未核对当前账号模型可用性；编辑输出边长512–2048，不能保证请求倍率。','- Agent图只是plan→verify，compile未配置PostgreSQLcheckpointer；正式断点实际在agent_runs.plan。该Pg依赖不能作为目标继续用Pg的理由。','- 原测试fixture强制mock/corner/none，fake_planner替代规划；会创建对象bucket和测试用户并删除test_用户；本轮未执行。','- 会话导出ZIP有manifest；批量ZIP源码无manifest，目标补齐单项清单；投放尺寸算法为letterbox，prompt字段不参与AI计算。','- 原代码版本与环境配置只是声明；源树没有找到LICENSE或模型权重文件。许可和缓存安装需实施前核实。','', '## 2. 原公开接口出处','', '| 接口 | 处理函数 | 源文件与行号 |','|---|---|---|']
    for p in sorted((SOURCE/'backend/app/routers').glob('*.py')):
        tree=ast.parse(p.read_text(encoding='utf-8'))
        prefix=''
        for node in ast.walk(tree):
            if isinstance(node,ast.Call) and isinstance(node.func,ast.Name) and node.func.id=='APIRouter':
                for k in node.keywords:
                    if k.arg=='prefix': prefix=ast.literal_eval(k.value)
        for node in tree.body:
            if not isinstance(node,(ast.FunctionDef,ast.AsyncFunctionDef)): continue
            for deco in node.decorator_list:
                if isinstance(deco,ast.Call) and isinstance(deco.func,ast.Attribute) and isinstance(deco.func.value,ast.Name) and deco.func.value.id=='router':
                    suffix=ast.literal_eval(deco.args[0]) if deco.args else ''
                    base='' if p.stem=='events' else '/api'
                    rel=p.relative_to(SOURCE)
                    lines.append(f'| `{deco.func.attr.upper()} {base}{prefix}{suffix}` | `{node.name}` | [{rel.as_posix()}]({source_link(rel)})：{deco.lineno} |')
    lines+=['','## 3. 注册工具参数与执行声明','','| 工具 | 参数类 | 排队/会话/隐藏参数声明 | 出处 |','|---|---|---|']
    for p in sorted((SOURCE/'backend/app/tools').glob('*.py')):
        tree=ast.parse(p.read_text(encoding='utf-8'))
        for node in tree.body:
            if not isinstance(node,ast.Assign) or not isinstance(node.value,ast.Call):continue
            call=node.value
            name=call.func.id if isinstance(call.func,ast.Name) else ''
            if name=='ToolSpec':
                kw={k.arg:k.value for k in call.keywords}
                name_node=kw['name']
                if isinstance(name_node,ast.Name):
                    definitions=[item for item in tree.body if isinstance(item,ast.Assign) and any(isinstance(t,ast.Name) and t.id==name_node.id for t in item.targets)]
                    name_node=definitions[0].value
                tool=ast.literal_eval(name_node); params=ast.unparse(kw.get('params'))
                meta='; '.join(f'{key}={ast.unparse(kw[key])}' for key in ['queued','session_required','agent_hidden','needs_approval'] if key in kw) or 'ToolSpec默认值'
            elif name=='_canvas' and len(call.args)>=5:
                tool=ast.literal_eval(call.args[0]);params=ast.unparse(call.args[3]);meta='queued=False; session_required=True'
            else:continue
            rel=p.relative_to(SOURCE)
            lines.append(f'| `{tool}` | `{params}` | `{meta}` | [{p.name}]({source_link(rel)})：{node.lineno} |')
    lines+=['','## 4. 原测试设计清单（未执行）','', '按静态函数名列出可追溯案例；参数化会扩展实际执行数量，下面数量是函数数，不是通过数。', '']
    count=0
    for p in sorted((SOURCE/'backend/tests').glob('test_*.py')):
        tree=ast.parse(p.read_text(encoding='utf-8'))
        tests=[n for n in tree.body if isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef)) and n.name.startswith('test_')]
        count+=len(tests)
        rel=p.relative_to(SOURCE)
        lines += [f'### {p.stem}：{len(tests)}个测试函数','',f'来源：[{p.name}]({source_link(rel)})。','']
        lines += [f'- `{n.name}`（第{n.lineno}行）' for n in tests]
        lines.append('')
    lines += [f'共{count}个测试函数，未执行，不记录通过数。','', '## 5. 文件指纹与阅读定位','', '| 原文件 | SHA256 |','|---|---|']
    for p in files:
        rel=p.relative_to(SOURCE)
        lines.append(f'| [{rel.as_posix()}]({source_link(rel)}) | `{hashlib.sha256(p.read_bytes()).hexdigest()}` |')
    target=ROOT/'源码核查附录.md'
    if target.exists():
        # 不覆盖已经人工回读与核对后的检查记录。
        return
    lines += ['', '## 6. 图稿检查记录','', '2026-10-02：图稿已生成，图稿与引用检查另由执行者实际回读后记录，不作为功能验收。', '']
    target.write_text('\n'.join(lines),encoding='utf-8')

if __name__ == '__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    create();editor();states();batch();controls();gallery();audit()
    # 保持唯一生成入口；首版图保留，当前菜单图和 HTML 由补充绘制模块生成。
    import runpy
    runpy.run_path(str(OUT/'补齐菜单图稿.py'),run_name='__main__')
    print(json.dumps({'png':15,'menu':5,'detail':5,'size':[W,H],'appendix':'源码核查附录.md'},ensure_ascii=False))
