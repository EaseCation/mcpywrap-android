# coding: utf-8
import os
import world
import application
import engine_notify_handler
level_id = CONFIG['worldId']
level_name = CONFIG['worldName'].encode('utf-8')
if not os.path.isfile(os.path.join(DATA_ROOT, 'minecraftWorlds', level_id, 'level.dat')):
    assert world.create_world(level_id, 2, False, False, '20261021')
    assert world.set_world_info(level_id, {'basic_info': {'game_type': 1, 'difficulty': 0, 'level_name': level_name, 'player_permission_level': 1}, 'option_info': {}, 'cheat_info': {'enable': True}})
application.instance.InitOfflinePlayer(0, 'LocalDev')
engine_notify_handler.instance.set_offline_start('1')
assert world.play_world(level_id, level_name, CONFIG['resource'], CONFIG['behavior'], {'user_name': 'LocalDev', 'user_id': '0'}, None, {'multiplayer_game_type': 0}, None)
