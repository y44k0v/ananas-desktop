import { combineReducers } from 'redux'

import AppSideBar from './AppSideBar'
import AppToolBar from './AppToolBar'
import AnalysisBoard from './AnalysisBoard'
import ExecutionEngine from './ExecutionEngine'
import Message from './Message'
import model from './model'
import settings from './settings'

const reducer = combineReducers({
  AppSideBar,
  AppToolBar,
  AnalysisBoard,
  ExecutionEngine, 
	Message,
  model,
	settings,
})

export default reducer
