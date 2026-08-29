export function resolveSlotGridLayout(totalSlots, configuredGroupSize, sortDirection) {
  const parsedTotal = Number(totalSlots)
  const total = Number.isInteger(parsedTotal) && parsedTotal > 0 ? parsedTotal : 0
  const parsedGroupSize = Number(configuredGroupSize)
  const groupSize = Number.isInteger(parsedGroupSize) && parsedGroupSize > 0
    ? Math.min(parsedGroupSize, Math.max(1, total))
    : Math.max(1, total)
  const direction = String(sortDirection || '').toUpperCase() === 'VERTICAL'
    ? 'VERTICAL'
    : 'HORIZONTAL'
  // 100/120 槽柜的实物面板固定为上下两半；排序方向不能改变物理柜门位置。
  const splitColumns = total === 100 ? 5 : (total === 120 ? 6 : 0)
  const columns = splitColumns > 0
    ? splitColumns
    : Math.max(1, Math.ceil(total / groupSize))

  return { total, groupSize, columns, direction }
}

export function resolveSlotGridPosition(index, layout) {
  const itemIndex = Number(index)
  if (!Number.isInteger(itemIndex) || itemIndex < 0) return {}

  const splitCabinet = (layout.total === 100 && layout.columns === 5) ||
    (layout.total === 120 && layout.columns === 6)
  if (splitCabinet) {
    const slotsPerHalf = layout.total / 2
    const slotsPerColumn = slotsPerHalf / layout.columns
    const isLowerHalf = itemIndex >= slotsPerHalf
    const indexInHalf = itemIndex % slotsPerHalf
    return {
      gridColumn: Math.floor(indexInHalf / slotsPerColumn) + 1,
      // 第 11 条 CSS 网格轨道留给中间分隔带，下半区从第 12 条开始。
      gridRow: (indexInHalf % slotsPerColumn) + 1 + (isLowerHalf ? slotsPerColumn + 1 : 0)
    }
  }

  if (layout.direction === 'VERTICAL') {
    return {
      gridColumn: Math.floor(itemIndex / layout.groupSize) + 1,
      gridRow: (itemIndex % layout.groupSize) + 1
    }
  }

  return {
    gridColumn: (itemIndex % layout.columns) + 1,
    gridRow: Math.floor(itemIndex / layout.columns) + 1
  }
}

export function resolveSlotGridCardMetrics(layout, viewport = {}) {
  const viewportWidth = Math.max(0, Number(viewport.width) || 0)
  const viewportHeight = Math.max(0, Number(viewport.height) || 0)
  const columns = Math.max(1, Number(layout.columns) || 1)
  const isCabinetHalfSplit = (
    (layout.total === 100 && columns === 5) ||
    (layout.total === 120 && columns === 6)
  )
  const rows = isCabinetHalfSplit
    ? 20
    : layout.direction === 'VERTICAL'
    ? Math.max(1, Math.min(layout.groupSize, layout.total || 1))
    : Math.max(1, Math.ceil(layout.total / columns))
  const compact = Math.min(viewportWidth || 160, viewportHeight || 100)
  const gap = Math.min(8, Math.max(2, compact * 0.014))
  const padding = Math.min(10, Math.max(3, compact * 0.018))
  const clamp = (value, min, max) => Math.min(max, Math.max(min, value))
  const halfDividerGap = isCabinetHalfSplit ? clamp(viewportHeight * 0.018, 10, 18) : 0
  const renderedRows = rows + (isCabinetHalfSplit ? 1 : 0)
  const availableWidth = Math.max(1, viewportWidth - padding * 2 - gap * (columns - 1))
  const availableHeight = Math.max(1, viewportHeight - padding * 2 - gap * (renderedRows - 1) - halfDividerGap)
  const cardWidth = viewportWidth > 0 ? availableWidth / columns : 150
  const cardHeight = viewportHeight > 0 ? availableHeight / rows : 88
  const cardSize = Math.min(cardWidth, cardHeight)

  return {
    cardWidth,
    cardHeight,
    rows,
    isCabinetHalfSplit,
    halfDividerGap,
    halfDividerLineHeight: isCabinetHalfSplit ? clamp(halfDividerGap * 0.5, 5, 9) : 0,
    gap,
    padding,
    gridWidth: viewportWidth || columns * cardWidth + Math.max(0, columns - 1) * gap + padding * 2,
    gridHeight: viewportHeight || rows * cardHeight + Math.max(0, rows - 1) * gap + padding * 2,
    numberFontSize: clamp(cardSize * 0.25, 10, 22),
    statusFontSize: clamp(cardSize * 0.19, 9, 18),
    cardPadding: clamp(cardSize * 0.1, 2, 10),
    statusMarkSize: clamp(cardSize * 0.1, 5, 9)
  }
}
