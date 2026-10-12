-- Shared layout math for a row of same-sized boxes, from picolo-rpg.
local function getCenteredRowRect(index: number, count: number, w: number, y: number): number, number
  local screenW = love.graphics.getDimensions()
  local startX = screenW / 2 - count * w / 2
  return startX + (index - 1) * w, y
end

local record Player
  name: string
  hp: integer
  update: function(self: Player, dt: number)
end

local enum Color
  "red"
  "green"
end

local type Point = {number}

function Player:heal(amount: number, source: Player): boolean
  return true
end

function geometry.shapes.area<T>(shape: T, items: {string: Point}): number
  local n: integer = #items
  return n
end
