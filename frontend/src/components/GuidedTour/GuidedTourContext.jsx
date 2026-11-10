import { createContext, useContext, useState, useCallback } from 'react';

const GuidedTourContext = createContext({
  run: false,
  startTour: () => {},
  stopTour: () => {},
});

// eslint-disable-next-line react-refresh/only-export-components
export const useGuidedTour = () => {
  return useContext(GuidedTourContext);
};

export const GuidedTourProvider = ({ children }) => {
  const [run, setRun] = useState(false);

  const startTour = useCallback(() => {
    localStorage.removeItem('has_seen_tour');
    setRun(true);
  }, []);

  const stopTour = useCallback(() => {
    setRun(false);
  }, []);

  return (
    <GuidedTourContext.Provider value={{ run, startTour, stopTour }}>
      {children}
    </GuidedTourContext.Provider>
  );
};
